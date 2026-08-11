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

package ltd.cdmi.hivemind.simulator.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.TopicConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 机场上云流程模拟：config 请求 → airport_bind_status → airport_organization_get → airport_organization_bind → update_topo 上线。
 * <p>时序遵循 DJI Cloud API 机场上云交互规范（update_topo 属于注册成功后的上线步骤，不在注册流程内）。
 * 通过 tid 匹配云端回复，实现异步等待。</p>
 */
@Service
public class DockOnlineService {

    private static final Logger log = LoggerFactory.getLogger(DockOnlineService.class);
    /** 等待云端回复超时时间（秒） */
    private static final long REPLY_TIMEOUT_SECONDS = 10;
    /** config 请求超时重试次数（参考真实设备行为：超时重试3次，全失败才停止注册） */
    private static final int CONFIG_RETRY_MAX = 3;
    /** config 请求重试间隔（秒） */
    private static final long CONFIG_RETRY_INTERVAL_SECONDS = 3;

    /** DJI 上云 API 组织绑定错误码（参考 hivemind OrganizationBindErrorCode），直接透传不加前缀 */
    public static final int BIND_CODE_INVALID = 210229;

    /** 上线流程结果：success + code（"0"=成功，P/S/M 码=诊断错误，DJI 码如 210229=协议层错误） */
    public record OnlineResult(boolean success, String code) {
        public static OnlineResult ok() { return new OnlineResult(true, "0"); }
        public static OnlineResult fail(DiagnosticCode code) { return new OnlineResult(false, code.code()); }
        public static OnlineResult fail(int djiResultCode) { return new OnlineResult(false, String.valueOf(djiResultCode)); }
    }

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final ObjectMapper objectMapper;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;

    /** tid → CompletableFuture，用于等待 status_reply / requests_reply */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingReplies = new ConcurrentHashMap<>();

    public DockOnlineService(SimulatorProperties props, MqttClientManager mqtt, DeviceState state,
                             ObjectMapper objectMapper, RuntimeConfig runtimeConfig,
                             DiagnosticLogRecorder diagnosticRecorder) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
        registerListeners();
    }

    /**
     * 注册 status_reply 和 requests_reply 监听器，匹配 tid 完成等待。
     */
    private void registerListeners() {
        String dockSn = runtimeConfig.getDockSn();
        mqtt.addListener(TopicConstants.topic(TopicConstants.STATUS_REPLY, dockSn), this::handleReply);
        mqtt.addListener(TopicConstants.topic(TopicConstants.REQUESTS_REPLY, dockSn), this::handleReply);
    }

    /**
     * 处理云端回复：按 tid 匹配并完成 pending future。
     */
    private void handleReply(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String tid = node.path("tid").asText();
            CompletableFuture<JsonNode> future = pendingReplies.remove(tid);
            if (future != null) {
                future.complete(node);
                log.debug("回复匹配成功 tid={}, method={}", tid, node.path("method").asText());
            }
        } catch (Exception e) {
            log.error("解析回复失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 上线流程 ====================

    /**
     * 执行完整上线流程。
     * <p>时序（与 DJI Cloud API 机场上云交互规范一致）：
     * <ol>
     *   <li>config 请求（获取 License 校验所需参数，超时重试3次间隔3秒）</li>
     *   <li>airport_bind_status 查询设备绑定信息</li>
     *   <li>airport_organization_get 查询组织信息（result=210229 表示绑定码错误）</li>
     *   <li>airport_organization_bind 绑定到组织（result=210229 表示绑定码错误）</li>
     *   <li>update_topo 上线（注册成功后通知平台设备拓扑，对齐 DJI 行为：超时不停止流程）</li>
     * </ol>
     * @return OnlineResult，success=true 表示上线成功
     */
    public OnlineResult online() {
        if (state.isOnline()) {
            log.warn("设备已在线，忽略重复上线");
            return OnlineResult.ok();
        }
        if (!mqtt.isConnected()) {
            log.warn("MQTT 未连接，无法执行上线流程（请检查 MQTT 主机/端口/用户名/密码是否正确）");
            return OnlineResult.fail(DiagnosticCode.SIMULATOR_MQTT_NOT_CONNECTED);
        }
        try {
            // ==================== 注册流程 ====================

            // 1. 发 config 请求获取 License 校验参数（超时重试3次，间隔3秒，全失败才停止注册）
            JsonNode configReply = null;
            for (int attempt = 1; attempt <= CONFIG_RETRY_MAX; attempt++) {
                configReply = sendRequest("config", Map.of(
                        "config_type", "json",
                        "config_scope", "product"
                ));
                if (configReply != null) {
                    break;
                }
                if (attempt < CONFIG_RETRY_MAX) {
                    log.warn("config 请求超时，平台无响应，{}秒后第{}次重试", CONFIG_RETRY_INTERVAL_SECONDS, attempt + 1);
                    try {
                        TimeUnit.SECONDS.sleep(CONFIG_RETRY_INTERVAL_SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("config 重试等待被中断，停止注册流程");
                        return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY);
                    }
                }
            }
            if (configReply == null) {
                log.warn("config 请求{}次均超时，平台无响应，停止注册流程", CONFIG_RETRY_MAX);
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY);
            }
            log.info("config 请求成功: app_id={}", configReply.path("data").path("app_id").asText());

            // 校验云端返回的 app_license 与本地配置（用户填写）一致，不一致停止注册
            // 若用户未填写 license（留空），则跳过校验，不模拟 License 认证
            String localLicense = runtimeConfig.getAppLicense();
            if (localLicense == null || localLicense.isBlank()) {
                log.info("本地未配置 app_license，跳过 License 校验");
            } else {
                String remoteLicense = configReply.path("data").path("app_license").asText();
                if (!localLicense.equals(remoteLicense)) {
                    log.warn("app_license 校验失败：本地与云端不一致（local={}, remote={}）", localLicense, remoteLicense);
                    return OnlineResult.fail(DiagnosticCode.PLATFORM_LICENSE_MISMATCH);
                }
                log.info("app_license 校验通过");
            }

            // 2. 发 airport_bind_status 查询绑定状态
            JsonNode bindStatusReply = sendRequest("airport_bind_status", Map.of(
                    "devices", List.of(
                            Map.of("sn", runtimeConfig.getDockSn()),
                            Map.of("sn", runtimeConfig.getDroneSn())
                    )
            ));
            if (bindStatusReply == null) {
                log.warn("airport_bind_status 超时，平台无响应，停止注册流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY);
            }
            log.info("airport_bind_status 回复: result={}", bindStatusReply.path("data").path("result").asText());

            // 3. 发 airport_organization_get 查询组织信息（绑定码运行时可配）
            //    hivemind 据此校验绑定码：result=210229 表示绑定码错误
            String bindCode = runtimeConfig.getDeviceBindingCode();
            JsonNode orgGetReply = sendRequest("airport_organization_get", Map.of(
                    "device_binding_code", bindCode
            ));
            if (orgGetReply == null) {
                log.warn("airport_organization_get 超时，平台无响应，停止注册流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY);
            }
            int orgGetResult = orgGetReply.path("data").path("result").asInt(-1);
            log.info("airport_organization_get 回复: result={}", orgGetResult);
            if (orgGetResult == BIND_CODE_INVALID) {
                log.warn("绑定码校验失败: device_binding_code={}", bindCode);
                return OnlineResult.fail(BIND_CODE_INVALID);
            }

            // 4. 发 airport_organization_bind 绑定到组织（组织ID/绑定码运行时可配）
            //    hivemind 据此将设备注册并绑定到项目：result=210229 表示绑定码错误
            String orgId = runtimeConfig.getOrganizationId();
            JsonNode orgBindReply = sendRequest("airport_organization_bind", Map.of(
                    "bind_devices", List.of(
                            Map.of(
                                    "sn", runtimeConfig.getDockSn(),
                                    "device_model_key", runtimeConfig.getDockType().modelKey(),
                                    "device_callsign", runtimeConfig.getDockSn(),
                                    "organization_id", orgId,
                                    "device_binding_code", bindCode
                            ),
                            Map.of(
                                    "sn", runtimeConfig.getDroneSn(),
                                    "device_model_key", runtimeConfig.getDroneType().modelKey(),
                                    "device_callsign", runtimeConfig.getDroneSn(),
                                    "organization_id", orgId,
                                    "device_binding_code", bindCode
                            )
                    ),
                    "organization_id", orgId
            ));
            if (orgBindReply == null) {
                log.warn("airport_organization_bind 超时，平台无响应，停止注册流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY);
            }
            int orgBindResult = orgBindReply.path("data").path("result").asInt(-1);
            log.info("airport_organization_bind 回复: result={}", orgBindResult);
            if (orgBindResult == BIND_CODE_INVALID) {
                log.warn("绑定失败: 绑定码错误");
                return OnlineResult.fail(BIND_CODE_INVALID);
            }

            log.info("机场注册成功: dockSn={}, droneSn={}", runtimeConfig.getDockSn(), runtimeConfig.getDroneSn());

            // ==================== 上线流程（注册成功后执行）====================

            // 5. 发 update_topo 上线（注册成功后通知平台设备拓扑）
            if (!sendUpdateTopo()) {
                // result≠0，平台拓扑更新失败，停止上线
                log.warn("update_topo 失败，停止上线流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY);
            }

            state.setOnline(true);
            // 上报 live_capacity，告知云端设备直播能力（hivemind 据此注册可用视频流）
            publishLiveCapacity();
            log.info("机场上线流程完成: dockSn={}, droneSn={}", runtimeConfig.getDockSn(), runtimeConfig.getDroneSn());
            return OnlineResult.ok();
        } catch (Exception e) {
            log.error("上线流程异常: {}", e.getMessage(), e);
            return OnlineResult.fail(DiagnosticCode.SIMULATOR_PARSE_BUG);
        }
    }

    /**
     * 仅执行上线流程（跳过注册），用于已注册设备的开机自动重连。
     * <p>设备关机前已注册成功（localStorage.registered=true），开机重连时只需：
     * <ol>
     *   <li>update_topo 上线（通知平台设备拓扑）</li>
     *   <li>标记在线 + 上报 live_capacity</li>
     * </ol>
     * 跳过 config/airport_bind_status/airport_organization_get/airport_organization_bind 注册步骤。
     * @return OnlineResult，success=true 表示上线成功
     */
    public OnlineResult onlineOnly() {
        if (state.isOnline()) {
            log.warn("设备已在线，忽略重复上线");
            return OnlineResult.ok();
        }
        if (!mqtt.isConnected()) {
            log.warn("MQTT 未连接，无法执行上线流程（请检查 MQTT 主机/端口/用户名/密码是否正确）");
            return OnlineResult.fail(DiagnosticCode.SIMULATOR_MQTT_NOT_CONNECTED);
        }
        try {
            log.info("跳过注册流程，直接上线（设备已注册）: dockSn={}", runtimeConfig.getDockSn());
            if (!sendUpdateTopo()) {
                log.warn("update_topo 失败，停止上线流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY);
            }
            state.setOnline(true);
            publishLiveCapacity();
            log.info("机场上线流程完成: dockSn={}, droneSn={}", runtimeConfig.getDockSn(), runtimeConfig.getDroneSn());
            return OnlineResult.ok();
        } catch (Exception e) {
            log.error("上线流程异常: {}", e.getMessage(), e);
            return OnlineResult.fail(DiagnosticCode.SIMULATOR_PARSE_BUG);
        }
    }

    /**
     * 下线：标记离线 + 发 update_topo 空 sub_devices 通知平台。
     * <p>MQTT 连接的断开由调用方（SimulatorController）处理。</p>
     */
    public void offline() {
        if (!state.isOnline()) {
            log.warn("设备已离线，忽略重复下线");
            return;
        }
        state.setOnline(false);  // 先标记离线，停止 OSD 上报

        // 发 update_topo 下线（sub_devices 为空表示下线）
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", 3);
        data.put("sub_type", 0);
        data.put("sub_devices", List.of());
        data.put("thing_version", "3.0.0.0");
        publishStatus("update_topo", tid, bid, data);

        log.info("机场已下线: dockSn={}", runtimeConfig.getDockSn());
    }

    /**
     * 飞行器休眠时发送 update_topo 通知平台飞行器下线。
     * <p>机场仍在线，仅飞行器从激活切换为休眠。发送空 sub_devices 的 update_topo。</p>
     * <p>TODO 待核实：真实机场在飞行器休眠时发送的 update_topo 中 sub_devices 字段格式。
     * 当前默认为空列表（与设备下线格式一致），需根据实际场景下的真实指令为准。</p>
     * <p>核实依据：DJI SDK 源码中 sub_devices 为空 → INBOUND_STATUS_OFFLINE；
     * https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/dock-feature-set/dock-device-management.html</p>
     */
    public void publishDroneSleepTopo() {
        if (!state.isOnline()) {
            log.warn("设备未上线，跳过飞行器休眠 update_topo");
            return;
        }
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", runtimeConfig.getDockType().getType());
        data.put("sub_type", runtimeConfig.getDockType().getSubType());
        data.put("sub_devices", List.of());
        data.put("thing_version", "3.0.0.0");
        publishStatus("update_topo", tid, bid, data);
        log.info("飞行器休眠，已发送 update_topo（sub_devices 为空）");
    }

    // ==================== 报文构造 ====================

    /**
     * 发送 update_topo 上线报文。
     * <p>发送后等待 status_reply：
     * <ul>
     *   <li>result=0 → 返回 true（上线成功）</li>
     *   <li>result≠0 → 返回 false（平台拓扑更新失败，停止上线）</li>
     *   <li>超时 → 返回 true（不停止上线，对齐 DJI 时序图行为）</li>
     * </ul>
     * TODO 待核实：真实机场收到 result≠0 后是否继续执行上线后续流程</p>
     *
     * @return true 表示继续上线，false 表示停止上线
     */
    private boolean sendUpdateTopo() {
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();

        DeviceType dockType = runtimeConfig.getDockType();
        DeviceType droneType = runtimeConfig.getDroneType();

        // DJI update_topo 的 data 顶层不含 domain 字段（在 sub_devices 元素中），否则被误判为 Autel
        // sub_devices 根据飞行器激活状态决定：激活时包含飞行器，休眠时为空
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", dockType.getType());
        data.put("sub_type", dockType.getSubType());
        if (state.isDroneActivated()) {
            data.put("sub_devices", List.of(
                    Map.of(
                            "sn", runtimeConfig.getDroneSn(),
                            "domain", droneType.getDomain(),
                            "type", droneType.getType(),
                            "sub_type", droneType.getSubType(),
                            "index", "A",
                            "firmware_version", "0.0.0.0"
                    )
            ));
        } else {
            data.put("sub_devices", List.of());
        }
        data.put("thing_version", "3.0.0.0");

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingReplies.put(tid, future);
        publishStatus("update_topo", tid, bid, data);

        try {
            JsonNode reply = future.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int result = reply.path("data").path("result").asInt(-1);
            if (result == 0) {
                log.info("update_topo 已收到 status_reply: result=0");
                return true;
            } else {
                log.warn("update_topo status_reply 返回非零: result={}，停止上线", result);
                return false;
            }
        } catch (Exception e) {
            pendingReplies.remove(tid);
            log.warn("等待 status_reply 超时（不影响上线，对齐 DJI 行为）: {}", e.getMessage());
            diagnosticRecorder.record(DiagnosticCode.PLATFORM_NO_REPLY, "update_topo",
                    "平台未回复 status_reply（超时 " + REPLY_TIMEOUT_SECONDS + "s），可能平台服务未启动或未实现 status_reply 回复");
            return true;
        }
    }

    /**
     * 发送 requests 请求（config/airport_bind_status/airport_organization_bind）并等待 requests_reply。
     * @param method 方法名
     * @param data 请求数据
     * @return 回复 JSON，超时返回 null
     */
    public JsonNode sendRequest(String method, Map<String, Object> data) {
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingReplies.put(tid, future);
        publishRequest(method, tid, bid, data);

        try {
            return future.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingReplies.remove(tid);
            log.warn("等待 requests_reply 超时: method={}, {}", method, e.getMessage());
            diagnosticRecorder.record(DiagnosticCode.PLATFORM_NO_REPLY, method,
                    "平台未回复 requests_reply（超时 " + REPLY_TIMEOUT_SECONDS + "s）");
            return null;
        }
    }

    /**
     * 发布 status 通道消息（sys/product/{sn}/status）。
     */
    private void publishStatus(String method, String tid, String bid, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", bid);
        envelope.put("data", data);
        envelope.put("tid", tid);
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("method", method);
        String topic = TopicConstants.topic(TopicConstants.STATUS, runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已发送 status: method={}, tid={}", method, tid);
    }

    /**
     * 发布 requests 通道消息（thing/product/{sn}/requests）。
     */
    private void publishRequest(String method, String tid, String bid, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", bid);
        envelope.put("data", data);
        envelope.put("tid", tid);
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("method", method);
        String topic = TopicConstants.topic(TopicConstants.REQUESTS, runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已发送 requests: method={}, tid={}", method, tid);
    }

    /**
     * 上报 live_capacity（直播能力），告知云端设备支持的直播视频流。
     * <p>topic: thing/product/{sn}/state（DJI 文档规定 live_capacity 的 pushMode=1，对应 state topic）。
     * hivemind 据此注册设备直播能力，缺失会导致云端无法发起直播。</p>
     * <p>核实依据：DJI Cloud API 直播功能文档「直播能力更新」：
     * - Topic: thing/product/{device_sn}/state
     * - 数据结构: live_capacity → available_video_number / coexist_video_number_max / device_list[] → camera_list[] → video_list[]
     * - 文档 URL: https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/live.html
     * </p>
     */
    private void publishLiveCapacity() {
        // 视频流信息（video_list 元素）
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("video_index", "normal-0");
        video.put("video_type", "normal");
        video.put("switchable_video_types", List.of("normal"));

        // 相机信息（camera_list 元素）
        Map<String, Object> camera = new LinkedHashMap<>();
        camera.put("camera_index", "165-0-7");
        camera.put("available_video_number", 1);
        camera.put("coexist_video_number_max", 1);
        camera.put("video_list", List.of(video));

        // 子设备信息（device_list 元素）
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("sn", runtimeConfig.getDroneSn());
        device.put("available_video_number", 1);
        device.put("coexist_video_number_max", 1);
        device.put("camera_list", List.of(camera));

        // 直播能力
        Map<String, Object> liveCapacity = new LinkedHashMap<>();
        liveCapacity.put("available_video_number", 1);
        liveCapacity.put("coexist_video_number_max", 1);
        liveCapacity.put("device_list", List.of(device));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("live_capacity", liveCapacity);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("data", data);
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());

        String topic = TopicConstants.topic(TopicConstants.STATE, runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已上报 live_capacity（state topic）");
    }

    /**
     * 推送飞行器 state 属性到 thing/product/{droneSn}/state。
     * <p>飞行器从休眠切换为激活时调用，推送所有 pushMode=1 的飞行器属性初始值。
     * 对齐 DJI 设备管理时序图中「设备（飞行器）属性推送 Topic: thing/product/{device_sn}/state」。</p>
     * <p>核实依据：DJI 飞行器设备属性文档，pushMode=1 的属性在状态变化时上报：
     * https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/properties.html</p>
     */
    public void publishDroneState() {
        if (!state.isOnline()) {
            log.warn("设备未上线，跳过 drone state 推送");
            return;
        }
        if (!mqtt.isConnected()) {
            log.warn("MQTT 未连接，跳过 drone state 推送");
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();

        // payloads — 负载状态
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("control_source", "A");
        payload.put("payload_index", "165-0-7");
        payload.put("firmware_version", "0.0.0.0");
        payload.put("sn", runtimeConfig.getDroneSn());
        data.put("payloads", List.of(payload));

        // wpmz_version — 航线解析库版本号
        data.put("wpmz_version", "1.0.2");

        // commander_mode_lost_action — 指点飞行失控动作
        data.put("commander_mode_lost_action", 0);

        // current_commander_flight_mode — 指点飞行模式当前值
        data.put("current_commander_flight_mode", 0);

        // commander_flight_height — 指点飞行高度
        data.put("commander_flight_height", 0.0);

        // mode_code_reason — 飞行器进入当前状态的原因
        data.put("mode_code_reason", 0);

        // firmware_version — 固件版本
        data.put("firmware_version", "0.0.0.0");

        // compatible_status — 固件一致性
        data.put("compatible_status", 0);

        // firmware_upgrade_status — 固件升级状态
        data.put("firmware_upgrade_status", 0);

        // home_longitude / home_latitude — Home 点位置
        data.put("home_longitude", runtimeConfig.getLocationLongitude());
        data.put("home_latitude", runtimeConfig.getLocationLatitude());

        // control_source — 当前控制源
        data.put("control_source", "A");

        // low_battery_warning_threshold — 低电量告警
        data.put("low_battery_warning_threshold", 50);

        // serious_low_battery_warning_threshold — 严重低电量告警
        data.put("serious_low_battery_warning_threshold", 20);

        // rth_mode / current_rth_mode — 返航高度模式（机场只支持设定高度=1）
        data.put("rth_mode", 1);
        data.put("current_rth_mode", 1);

        // psdk_ui_resource — psdk ui 资源包
        data.put("psdk_ui_resource", List.of());

        // psdk_widget_values — psdk 负载设备属性值
        data.put("psdk_widget_values", List.of());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("data", data);
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());

        String topic = TopicConstants.topic(TopicConstants.STATE, runtimeConfig.getDroneSn());
        mqtt.publishJson(topic, envelope);
        log.info("已推送 drone state（{} 属性）", data.size());
    }
}
