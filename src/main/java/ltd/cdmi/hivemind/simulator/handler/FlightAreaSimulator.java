// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package ltd.cdmi.hivemind.simulator.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 自定义飞行区模拟器（Dock1/Dock2/Dock3）。
 * <p>协议参考：DJI Cloud API 自定义飞行区（Topic=thing/product/{gateway_sn}/events|services|requests）。
 * 覆盖 4 个协议交互：
 * <ul>
 *   <li>flight_areas_drone_location（Event, need_reply=0）：飞行器位置告警推送，data 为对象含 drone_locations 数组</li>
 *   <li>flight_areas_sync_progress（Event, need_reply=1）：文件同步进度上报，data 含 status/reason/file</li>
 *   <li>flight_areas_get（Requests）：设备主动获取飞行区文件，等待 requests_reply（data=null）</li>
 *   <li>flight_areas_update（Service）：平台下发更新通知，设备应答 result=0 并自动联动 get</li>
 * </ul>
 * <p>核实依据：[Dock1/Dock2/Dock3 wayline.html] 自定义飞行区（三版本协议结构一致）</p>
 */
@Component
public class FlightAreaSimulator {

    private static final Logger log = LoggerFactory.getLogger(FlightAreaSimulator.class);

    /** requests_reply 等待超时（秒） */
    private static final long REPLY_TIMEOUT_SECONDS = 10;

    /** update → get 自动联动的延迟（毫秒），确保 services_reply 先发出 */
    private static final long UPDATE_GET_DELAY_MS = 100;

    /** 文件名格式：geofence_{fileMD5}.json（Dock1/Dock2 规范，fileMD5 为 32 位十六进制 MD5 值） */
    private static final java.util.regex.Pattern FILE_NAME_PATTERN =
            java.util.regex.Pattern.compile("geofence_[a-fA-F0-9]{32}\\.json");

    private final MqttClientManager mqtt;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final ObjectMapper objectMapper;
    private final DockTopicSchema dockTopicSchema;

    /** tid → CompletableFuture，用于等待 requests_reply */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingReplies = new ConcurrentHashMap<>();

    /** 已注册 requests_reply 监听器的 dockSn（dockSn 变化时需重新注册） */
    private volatile String registeredReplyDockSn;

    /** update→get 联动推断是否已记录 M-2 日志（去重） */
    private volatile boolean updateGetInferenceLogged = false;

    /** 文件名校验→sync_progress 联动推断是否已记录 M-2 日志（去重） */
    private volatile boolean fileNameCheckInferenceLogged = false;

    public FlightAreaSimulator(MqttClientManager mqtt, RuntimeConfig runtimeConfig,
                               DiagnosticLogRecorder diagnosticRecorder,
                               ObjectMapper objectMapper,
                               DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
        this.objectMapper = objectMapper;
        this.dockTopicSchema = dockTopicSchema;
    }

    /** requests_reply 等待超时（秒），子类可覆盖用于测试 */
    protected long replyTimeoutSeconds() {
        return REPLY_TIMEOUT_SECONDS;
    }

    // ==================== 1. flight_areas_drone_location（Event, need_reply=0） ====================

    /**
     * 触发飞行器位置告警推送。
     * <p>通过 events Topic 发送 method=flight_areas_drone_location 事件，need_reply=0（单向通知）。</p>
     *
     * @param locations 飞行区位置信息列表（支持一次上报多个飞行区）
     * @return 触发结果：成功时 success=true；失败时 success=false 且包含 code/message
     */
    public TriggerResult triggerDroneLocation(List<DroneLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            return TriggerResult.fail("INVALID_LOCATIONS", "位置列表为空");
        }
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报飞行器位置");
        }

        publishDroneLocationEvent(locations);
        log.info("飞行区位置告警已上报: count={}", locations.size());
        return TriggerResult.ok(locations.size());
    }

    /**
     * 构造并发送 flight_areas_drone_location 事件报文。
     * <p>data 是对象，包含 drone_locations 数组（非 data 直接为数组，与 airsense_warning 不同）。</p>
     */
    private void publishDroneLocationEvent(List<DroneLocation> locations) {
        List<Map<String, Object>> droneLocations = locations.stream()
                .map(this::buildDroneLocationItem)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("drone_locations", droneLocations);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("need_reply", 0);  // 单向通知，不需平台回复
        envelope.put("gateway", runtimeConfig.getDockSn());
        envelope.put("method", "flight_areas_drone_location");
        envelope.put("data", data);

        String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
    }

    /**
     * 构造单个飞行区位置项。
     * <p>字段顺序按 DJI Example 字母序排列：area_distance, area_id, is_in_area。</p>
     * <p>注：area_id 在 Dock2 协议表格中明确列出（区域唯一 ID），Dock3 表格遗漏但 Example 包含，已交叉验证。</p>
     */
    private Map<String, Object> buildDroneLocationItem(DroneLocation loc) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("area_distance", loc.areaDistance());
        item.put("area_id", loc.areaId());
        item.put("is_in_area", loc.isInArea());
        return item;
    }

    // ==================== 2. flight_areas_sync_progress（Event, need_reply=1） ====================

    /**
     * 触发文件同步进度上报。
     * <p>通过 events Topic 发送 method=flight_areas_sync_progress 事件，need_reply=1（需平台回复）。</p>
     *
     * @param status 同步状态（fail/switch_fail/synchronized/synchronizing/wait_sync）
     * @param reason 返回码（0=成功, 1-13=失败原因）
     * @param file 文件信息（可为 null）
     * @return 触发结果
     */
    public TriggerResult triggerSyncProgress(SyncStatus status, int reason, FlightAreaFile file) {
        if (status == null) {
            return TriggerResult.fail("INVALID_STATUS", "同步状态为空");
        }
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报同步进度");
        }

        publishSyncProgressEvent(status, reason, file);
        log.info("飞行区同步进度已上报: status={}, reason={}", status.code(), reason);
        return TriggerResult.ok(1);
    }

    /**
     * 构造并发送 flight_areas_sync_progress 事件报文。
     * <p>data 含 status（enum_string）、reason（int）、file（struct: name/checksum）。</p>
     */
    private void publishSyncProgressEvent(SyncStatus status, int reason, FlightAreaFile file) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.code());
        data.put("reason", reason);
        if (file != null) {
            Map<String, Object> fileMap = new LinkedHashMap<>();
            fileMap.put("name", file.name());
            fileMap.put("checksum", file.checksum());
            data.put("file", fileMap);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("need_reply", 1);  // 需平台回复
        envelope.put("gateway", runtimeConfig.getDockSn());
        envelope.put("method", "flight_areas_sync_progress");
        envelope.put("data", data);

        String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
    }

    // ==================== 3. flight_areas_get（Requests） ====================

    /**
     * 发起飞行区文件获取请求，等待 requests_reply。
     * <p>通过 requests Topic 发送 method=flight_areas_get（data=null），
     * 等待平台回复 output.files 列表。</p>
     *
     * @return 请求结果：成功时 success=true 且包含 reply 内容；失败时 success=false
     */
    public RequestResult requestFlightAreas() {
        if (!mqtt.isConnected()) {
            return RequestResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法发起飞行区获取");
        }

        ensureReplyListener();

        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingReplies.put(tid, future);

        // 发送 requests 消息（data=null）
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", bid);
        envelope.put("tid", tid);
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("gateway", runtimeConfig.getDockSn());
        envelope.put("method", "flight_areas_get");
        envelope.put("data", null);

        String topic = dockTopicSchema.topic(dockTopicSchema.requests(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已发送 requests: method=flight_areas_get, tid={}", tid);

        try {
            JsonNode reply = future.get(replyTimeoutSeconds(), TimeUnit.SECONDS);
            int result = reply.path("data").path("result").asInt(-1);
            log.info("收到 requests_reply: method=flight_areas_get, result={}", result);

            // 校验文件名格式（Dock2 规范：geofence_{fileMD5}.json），校验失败自动上报 sync_progress(fail)
            Boolean fileValid = validateReplyFiles(reply);
            return RequestResult.ok(reply, fileValid);
        } catch (Exception e) {
            pendingReplies.remove(tid);
            log.warn("等待 requests_reply 超时: method=flight_areas_get, {}", e.getMessage());
            return RequestResult.fail("REPLY_TIMEOUT", "等待平台回复超时");
        }
    }

    // ==================== 4. flight_areas_update（Service） ====================

    /**
     * 处理平台下发的 flight_areas_update 指令。
     * <p>回 services_reply(result=0)，并异步自动联动 flight_areas_get。
     * <p>⚠️ update 与 get 的联动关系 DJI 文档未明确，为合理推断（平台通知更新→设备主动拉取），
     * 记录 M-2 诊断日志（DiagnosticCode.MONITOR_SIMULATOR_INFERENCE），待真机验证。</p>
     *
     * @return services_reply 的 data：{ result: 0 }
     */
    public Map<String, Object> handleServiceUpdate() {
        // 记录 M-2 诊断日志：update→get 自动联动为推断行为，待真机验证
        if (!updateGetInferenceLogged) {
            updateGetInferenceLogged = true;
            diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                    "flight_areas_update",
                    "update→get 自动联动为推断行为（DJI 文档未明确），待真机验证");
        }

        // 异步触发 flight_areas_get（延迟确保 services_reply 先发出）
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(UPDATE_GET_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            requestFlightAreas();
        });

        return Map.of("result", 0);
    }

    // ==================== requests_reply 监听 ====================

    /**
     * 确保 requests_reply 监听器已注册（dockSn 变化时重新注册）。
     * <p>与 WaylineTaskSimulator 的 requests 机制独立（各自按 tid 匹配，互不干扰）。</p>
     */
    private void ensureReplyListener() {
        String dockSn = runtimeConfig.getDockSn();
        if (dockSn.equals(registeredReplyDockSn)) {
            return;
        }
        String topic = dockTopicSchema.topic(dockTopicSchema.requestsReply(), dockSn);
        mqtt.addListener(topic, this::handleReply);
        registeredReplyDockSn = dockSn;
        log.info("FlightAreaSimulator 已注册 requests_reply 监听器: dockSn={}", dockSn);
    }

    /**
     * 处理 requests_reply：按 tid 匹配并完成 pending future。
     */
    private void handleReply(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String tid = node.path("tid").asText();
            CompletableFuture<JsonNode> future = pendingReplies.remove(tid);
            if (future != null) {
                future.complete(node);
                log.debug("requests_reply 匹配成功 tid={}, method={}", tid, node.path("method").asText());
            }
        } catch (Exception e) {
            log.error("解析 requests_reply 失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 文件名格式校验（Dock2 规范） ====================

    /**
     * 校验 requests_reply 中的文件名格式（Dock2 规范：geofence_{fileMD5}.json）。
     * <p>fileMD5 为 32 位十六进制 MD5 值。模拟器只校验格式，不校验 MD5 值与文件内容的一致性（未下载文件）。
     * <p>校验失败时自动上报 sync_progress(fail, reason=1 "解析云端返回的文件信息失败")。
     * <p>⚠️ 校验失败后自动上报 sync_progress 为推断行为，记录 M-2 诊断日志。</p>
     *
     * @return true=全部合规, false=有不合规, null=无文件
     */
    private Boolean validateReplyFiles(JsonNode reply) {
        JsonNode files = reply.path("data").path("output").path("files");
        if (!files.isArray() || files.isEmpty()) {
            return null;
        }

        String firstInvalidName = null;
        for (JsonNode file : files) {
            String name = file.path("name").asText(null);
            if (!isValidFileName(name)) {
                if (firstInvalidName == null) {
                    firstInvalidName = name;
                }
                log.warn("文件名格式校验失败: name={}, 期望格式=geofence_{{fileMD5}}.json (32位hex)", name);
            }
        }

        if (firstInvalidName != null) {
            // 记录 M-2 诊断日志：校验失败后自动上报 sync_progress 为推断行为
            if (!fileNameCheckInferenceLogged) {
                fileNameCheckInferenceLogged = true;
                diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                        "flight_areas_sync_progress",
                        "文件名校验失败后自动上报 sync_progress(fail, reason=1) 为推断行为（DJI 文档未明确联动），待真机验证");
            }

            // 自动上报 sync_progress(fail, reason=1)，file 为第一个不合规文件
            publishSyncProgressEvent(SyncStatus.FAIL, 1, new FlightAreaFile(firstInvalidName, ""));
            log.warn("已自动上报 sync_progress(fail, reason=1): invalidFileName={}", firstInvalidName);
            return false;
        }

        return true;
    }

    /** 校验文件名格式：geofence_{fileMD5}.json（fileMD5 为 32 位十六进制） */
    private boolean isValidFileName(String name) {
        return name != null && FILE_NAME_PATTERN.matcher(name).matches();
    }

    // ==================== 数据模型 ====================

    /** 飞行器位置信息（单个飞行区） */
    public record DroneLocation(
            String areaId,           // 飞行区 ID（Example 中出现，协议表格未列出）
            double areaDistance,      // 距离飞行边界距离
            boolean isInArea          // 是否在自定义飞行区内
    ) {}

    /** 自定义飞行区文件信息 */
    public record FlightAreaFile(
            String name,              // 文件名
            String checksum           // SHA256 签名
    ) {}

    /** 同步状态枚举（DJI 协议 enum_string） */
    public enum SyncStatus {
        FAIL("fail"),
        SWITCH_FAIL("switch_fail"),
        SYNCHRONIZED("synchronized"),
        SYNCHRONIZING("synchronizing"),
        WAIT_SYNC("wait_sync");

        private final String code;
        SyncStatus(String code) { this.code = code; }
        public String code() { return code; }

        /** 从协议字符串解析枚举，无效时返回 null */
        public static SyncStatus fromCode(String code) {
            if (code == null) return null;
            for (SyncStatus s : values()) {
                if (s.code.equals(code)) return s;
            }
            return null;
        }
    }

    /** 触发结果（drone_location / sync_progress） */
    public record TriggerResult(boolean success, String code, String message, int count) {
        public static TriggerResult ok(int count) {
            return new TriggerResult(true, null, null, count);
        }
        public static TriggerResult fail(String code, String message) {
            return new TriggerResult(false, code, message, 0);
        }
    }

    /** 请求结果（flight_areas_get） */
    public record RequestResult(boolean success, String code, String message, JsonNode reply, Boolean fileValid) {
        public static RequestResult ok(JsonNode reply, Boolean fileValid) {
            return new RequestResult(true, null, null, reply, fileValid);
        }
        public static RequestResult fail(String code, String message) {
            return new RequestResult(false, code, message, null, null);
        }
    }
}
