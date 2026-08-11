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
import jakarta.annotation.PostConstruct;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.TopicConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 媒体管理模拟器。
 * <p>处理 DJI Cloud API「媒体管理」协议（三 Dock 一致，无差异）：
 * <ul>
 *   <li>Event 上行：file_upload_callback（文件上传结果，need_reply=1，等待 events_reply）、
 *       highest_priority_upload_flighttask_media（优先级上报，need_reply=1）</li>
 *   <li>Service 下行：upload_flighttask_media_prioritize（调整最高优先级）→ services_reply result=0</li>
 *   <li>Requests 上行：storage_config_get（获取 STS 临时凭证）→ 解析 bucket/credentials/endpoint/object_key_prefix</li>
 *   <li>S3 文件上传（非 MQTT）：使用 STS 凭证通过 {@link MediaUploader} 上传本地文件到对象存储</li>
 * </ul>
 * <p>降级策略：media-dir 未配置或 STS 凭证获取失败时跳过文件上传，仅发 file_upload_callback（元数据上报）。
 * <p>详见 DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html">媒体管理（Dock3）</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/file.html">Dock2</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/file.html">Dock1</a>。
 */
@Component
public class MediaUploadSimulator {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadSimulator.class);

    /** 等待云端 events_reply 超时时间（秒），超时不阻塞后续上传 */
    private static final long EVENT_REPLY_TIMEOUT_SECONDS = 10;

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final ObjectMapper objectMapper;
    private final DockOnlineService onlineService;
    private final ServiceCommandHandler commandHandler;
    private final MediaUploader mediaUploader;
    private final RuntimeConfig runtimeConfig;

    /** 已上报的媒体文件列表（供 Web 控制台展示） */
    private final List<Map<String, Object>> uploadedFiles = new CopyOnWriteArrayList<>();
    /** tid → CompletableFuture，用于等待 events_reply（need_reply=1 的事件） */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingEventReplies = new ConcurrentHashMap<>();
    /** 当前最高优先级 flight_id（upload_flighttask_media_prioritize 设置） */
    private volatile String priorityFlightId;

    public MediaUploadSimulator(SimulatorProperties props, MqttClientManager mqtt,
                                ObjectMapper objectMapper, DockOnlineService onlineService,
                                ServiceCommandHandler commandHandler,
                                MediaUploader mediaUploader, RuntimeConfig runtimeConfig) {
        this.props = props;
        this.mqtt = mqtt;
        this.objectMapper = objectMapper;
        this.onlineService = onlineService;
        this.commandHandler = commandHandler;
        this.mediaUploader = mediaUploader;
        this.runtimeConfig = runtimeConfig;
        registerListeners();
    }

    /**
     * 注册 events_reply 监听器，按 tid 匹配等待中的事件回复。
     * <p>模式与 {@link DockOnlineService} 的 requests_reply 等待一致。</p>
     */
    private void registerListeners() {
        String dockSn = runtimeConfig.getDockSn();
        mqtt.addListener(TopicConstants.topic(TopicConstants.EVENTS_REPLY, dockSn), this::handleEventReply);
    }

    @PostConstruct
    public void init() {
        commandHandler.setMediaHandler(this::handleMediaCommand);
        log.info("MediaUploadSimulator 已注册媒体命令处理器");
    }

    /**
     * 处理 events_reply：按 tid 匹配并完成 pending future。
     */
    private void handleEventReply(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String tid = node.path("tid").asText();
            CompletableFuture<JsonNode> future = pendingEventReplies.remove(tid);
            if (future != null) {
                future.complete(node);
                log.debug("events_reply 匹配成功 tid={}, method={}", tid, node.path("method").asText());
            }
        } catch (Exception e) {
            log.error("解析 events_reply 失败: {}", e.getMessage(), e);
        }
    }

    // ==================== Service 指令处理 ====================

    /**
     * 统一路由媒体相关 services 命令（由 ServiceCommandHandler 调用）。
     * @param method 指令方法名
     * @param data   指令 data
     * @return services_reply 的 output（含 result 字段）
     */
    public Map<String, Object> handleMediaCommand(String method, JsonNode data) {
        log.info("处理媒体命令: method={}", method);

        return switch (method) {
            case "upload_flighttask_media_prioritize" -> handlePrioritize(data);
            default -> Map.of("result", 0);
        };
    }

    /**
     * 调整上传文件为最高优先级：记录 flight_id，回 result=0。
     * <p>核实依据：[Dock3 media.html] Service upload_flighttask_media_prioritize，Data 含 flight_id。</p>
     */
    private Map<String, Object> handlePrioritize(JsonNode data) {
        if (data != null) {
            String flightId = data.path("flight_id").asText();
            this.priorityFlightId = flightId;
            log.info("媒体上传优先级已调整: flightId={}", flightId);
        }
        return Map.of("result", 0);
    }

    // ==================== 媒体上传流程 ====================

    /**
     * 模拟媒体上传流程（航线任务完成后或手动触发调用）。
     * <p>时序：storage_config_get → highest_priority_upload_flighttask_media → 逐个上传文件 + file_upload_callback</p>
     * <p>降级策略：media-dir 未配置或 STS 凭证获取失败时跳过文件上传，仅发 file_upload_callback（元数据上报）。</p>
     *
     * @param flightId  任务 ID
     * @param fileCount 模拟生成的文件数量
     */
    public void simulateMediaUpload(String flightId, int fileCount) {
        log.info("开始模拟媒体上传: flightId={}, fileCount={}", flightId, fileCount);

        // 1. 发 storage_config_get 请求获取 STS 凭证
        StorageConfig storageConfig = fetchStorageConfig();
        boolean canUpload = storageConfig != null && storageConfig.isValid();
        if (!canUpload) {
            log.warn("STS 凭证获取失败或无效，降级为仅元数据上报（虚构 object_key）");
        }

        // 2. 从 media-dir 读取本地媒体文件
        List<Path> localFiles = mediaUploader.listMediaFiles(runtimeConfig.getMediaDir());
        boolean hasRealFiles = !localFiles.isEmpty();
        if (hasRealFiles) {
            log.info("从 media-dir 读取到 {} 个文件: {}", localFiles.size(), runtimeConfig.getMediaDir());
        } else {
            log.info("media-dir 未配置或无文件，使用虚构文件名上报");
        }

        // 3. 上报 highest_priority_upload_flighttask_media（等待 events_reply）
        publishHighestPriority(flightId);

        // 4. 逐个上传文件并上报 file_upload_callback
        String objectKeyPrefix = (canUpload && storageConfig.objectKeyPrefix() != null)
                ? storageConfig.objectKeyPrefix() : "sim";
        int uploadedCount = 0;
        for (int i = 0; i < fileCount; i++) {
            String fileName;
            boolean uploaded = false;

            if (hasRealFiles) {
                // 循环使用目录中的文件（fileCount > 文件数时重复使用）
                Path localFile = localFiles.get(i % localFiles.size());
                fileName = localFile.getFileName().toString();

                if (canUpload) {
                    String objectKey = objectKeyPrefix + "/" + flightId + "/" + fileName;
                    uploaded = mediaUploader.upload(localFile, storageConfig, objectKey);
                    if (uploaded) uploadedCount++;
                }
            } else {
                // 无本地文件，使用虚构文件名
                fileName = "SIM_" + flightId + "_" + (i + 1) + ".jpg";
            }

            publishFileUploadCallback(flightId, fileName, i, fileCount, objectKeyPrefix);
            uploadedFiles.add(Map.of(
                    "flight_id", flightId,
                    "name", fileName,
                    "uploaded", uploaded,
                    "upload_time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            ));
        }
        log.info("媒体上传完成: flightId={}, 已上报 {} 个文件, 实际上传 {} 个文件",
                flightId, fileCount, uploadedCount);
    }

    /**
     * 发送 storage_config_get 请求并解析 STS 凭证。
     * @return StorageConfig，超时或解析失败返回 null
     */
    private StorageConfig fetchStorageConfig() {
        JsonNode configReply = onlineService.sendRequest("storage_config_get", Map.of("module", 0));
        StorageConfig config = StorageConfig.fromReply(configReply);
        if (config != null && config.isValid()) {
            log.info("获取存储凭证成功: bucket={}, endpoint={}, provider={}, object_key_prefix={}",
                    config.bucket(), config.endpoint(), config.provider(), config.objectKeyPrefix());
        } else {
            log.warn("获取存储凭证失败或无效，降级为仅元数据上报");
        }
        return config;
    }

    /**
     * 发布 highest_priority_upload_flighttask_media 事件（need_reply=1，等待 events_reply）。
     * <p>核实依据：[Dock3 media.html] Event，Data 含 flight_id。</p>
     */
    private void publishHighestPriority(String flightId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flight_id", flightId);
        publishEventAndWaitReply("highest_priority_upload_flighttask_media", data);
    }

    /**
     * 发布 file_upload_callback 事件（need_reply=1，等待 events_reply）。
     * @param flightId 任务 ID
     * @param fileName 文件名
     * @param index 当前文件索引（从 0 开始）
     * @param totalFiles 本次任务预期上传的文件总数
     * @param objectKeyPrefix 对象存储 Key 前缀（来自 storage_config_get）
     */
    private void publishFileUploadCallback(String flightId, String fileName, int index,
                                           int totalFiles, String objectKeyPrefix) {
        // ext 扩展信息
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("drone_model_key", runtimeConfig.getDroneType().modelKey());
        ext.put("flight_id", flightId);
        ext.put("is_original", true);
        ext.put("payload_model_key", runtimeConfig.getDroneType().modelKey());

        // metadata 媒体元数据
        Map<String, Object> shootPosition = new LinkedHashMap<>();
        shootPosition.put("lat", runtimeConfig.getLocationLatitude());
        shootPosition.put("lng", runtimeConfig.getLocationLongitude());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("absolute_altitude", runtimeConfig.getLocationHeight() + 50);
        metadata.put("create_time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        metadata.put("gimbal_yaw_degree", String.valueOf(index * 30));
        metadata.put("relative_altitude", 50.0);
        metadata.put("shoot_position", shootPosition);

        // file 文件信息（object_key 拼接 object_key_prefix）
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("cloud_to_cloud_id", "DEFAULT");
        file.put("ext", ext);
        file.put("metadata", metadata);
        file.put("name", fileName);
        file.put("object_key", objectKeyPrefix + "/" + flightId + "/" + fileName);
        file.put("path", flightId);

        // flight_task 字段（hivemind 据此统计文件上传计数）
        Map<String, Object> flightTask = new LinkedHashMap<>();
        flightTask.put("uploaded_file_count", index + 1);
        flightTask.put("expected_file_count", totalFiles);

        // 完整事件 data
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", file);
        data.put("flight_task", flightTask);

        publishEventAndWaitReply("file_upload_callback", data);
        log.info("已上报媒体文件: flightId={}, file={}", flightId, fileName);
    }

    /**
     * 发布事件并等待 events_reply（need_reply=1）。
     * <p>用 tid 关联 CompletableFuture，超时不阻塞流程（warn 日志后继续）。</p>
     * @param method 事件方法名
     * @param data 事件 data
     */
    private void publishEventAndWaitReply(String method, Map<String, Object> data) {
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", bid);
        envelope.put("data", data);
        envelope.put("gateway", runtimeConfig.getDockSn());
        envelope.put("method", method);
        envelope.put("need_reply", 1);
        envelope.put("tid", tid);
        envelope.put("timestamp", System.currentTimeMillis());

        // 注册等待 future
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingEventReplies.put(tid, future);

        String topic = TopicConstants.topic(TopicConstants.EVENTS, runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);

        // 等待 events_reply
        try {
            JsonNode reply = future.get(eventReplyTimeoutSeconds(), TimeUnit.SECONDS);
            int result = reply.path("data").path("result").asInt(-1);
            if (result == 0) {
                log.debug("收到 events_reply: method={}, result=0", method);
            } else {
                log.warn("events_reply 返回非零: method={}, result={}", method, result);
            }
        } catch (Exception e) {
            pendingEventReplies.remove(tid);
            log.warn("等待 events_reply 超时（不阻塞后续上传）: method={}, {}", method, e.getMessage());
        }
    }

    /**
     * 等待 events_reply 超时时间（秒），子类可覆盖以加速测试。
     * <p>默认 10 秒；超时不阻塞后续上传流程。</p>
     */
    protected long eventReplyTimeoutSeconds() {
        return EVENT_REPLY_TIMEOUT_SECONDS;
    }

    /**
     * 获取已上传的媒体文件列表（供 Web 控制台使用）。
     */
    public List<Map<String, Object>> getUploadedFiles() {
        return new ArrayList<>(uploadedFiles);
    }

    /** 获取当前最高优先级 flight_id（upload_flighttask_media_prioritize 设置）。 */
    public String getPriorityFlightId() {
        return priorityFlightId;
    }
}
