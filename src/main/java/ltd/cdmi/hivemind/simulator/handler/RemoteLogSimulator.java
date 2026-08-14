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
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 远程日志模拟器（Dock1/Dock2/Dock3 [log-upload.html]）。
 *
 * <p>处理 DJI Cloud API「远程日志」协议：
 * <ul>
 *   <li>fileupload_start（Service 下行）：发起日志文件上传，返回 result=0，异步模拟上传进度</li>
 *   <li>fileupload_update（Service 下行）：上传状态更新（取消上传），返回 result=0</li>
 *   <li>fileupload_list（Service 下行）：获取设备可上传的文件列表，返回 result=0 + files[]</li>
 *   <li>fileupload_progress（Event 上行）：文件上传进度通知，need_reply=0 单向通知</li>
 * </ul>
 *
 * <p>fileupload_start 收到后自动模拟上传进度（in_progress→ok + percent 递增），
 * 与 RemoteDebugSimulator 异步 Job 模式一致。
 *
 * <p>注意：DJI 文档 Column 表中进度字段拼写为 `prgress`（疑似拼写错误），
 * Example 中为 `progress`（正确拼写）。模拟器使用 `progress`，记录 M-2 诊断日志。
 */
@Component
public class RemoteLogSimulator {

    private static final Logger log = LoggerFactory.getLogger(RemoteLogSimulator.class);

    /** 远程日志同步 Service 指令集 */
    private static final Set<String> REMOTE_LOG_SERVICE_METHODS = Set.of("fileupload_start", "fileupload_update", "fileupload_list");

    /** 进度模拟间隔（秒） */
    private static final long PROGRESS_INTERVAL_SECONDS = 2;

    /** progress.current_step 模拟值（in_progress 阶段） */
    private static final int CURRENT_STEP = 19;
    /** progress.total_step 模拟值（与 DJI Example 一致，仅 dock 模块携带） */
    private static final int TOTAL_STEP = 30;
    /** progress.upload_rate 模拟值（byte/s） */
    private static final int UPLOAD_RATE = 1024;

    /** fileupload_list 模拟日志文件数（每个模块） */
    private static final int MOCK_LOG_FILE_COUNT = 2;
    /** fileupload_list 模拟日志文件大小（飞行器，byte） */
    private static final int MOCK_LOG_SIZE_DRONE = 33789;
    /** fileupload_list 模拟日志文件大小（机场，byte） */
    private static final int MOCK_LOG_SIZE_DOCK = 36772;
    /** fileupload_list 模拟 boot_index 基数（飞行器） */
    private static final int MOCK_BOOT_INDEX_BASE_DRONE = 1000;
    /** fileupload_list 模拟 boot_index 基数（机场） */
    private static final int MOCK_BOOT_INDEX_BASE_DOCK = 3000;

    private final MqttClientManager mqtt;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    /** 当前上传任务列表 */
    private final List<Map<String, Object>> currentUploadFiles = new ArrayList<>();

    /** 当前异步上传任务引用（用于取消） */
    private volatile ScheduledFuture<?> uploadTask;

    private final ScheduledExecutorService scheduler;

    public RemoteLogSimulator(MqttClientManager mqtt, RuntimeConfig runtimeConfig,
                              DiagnosticLogRecorder diagnosticRecorder,
                              DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "remote-log-scheduler");
            t.setDaemon(true);
            return t;
        });

        // M-2 诊断日志：fileupload_progress.progress 字段集差异
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                "fileupload_progress.progress",
                "DJI log-upload.html fileupload_progress Column 表仅列 3 字段（progress/finish_time/upload_rate），"
                        + "Example 实际含 7 字段（current_step/finish_time/progress/result/status/upload_rate，"
                        + "dock 模块额外含 total_step）。Dock3 Column 表将 progress 误写为 'prgress'，"
                        + "Dock1/Dock2 Column 表拼写正确。"
                        + "模拟器以 Example 为准使用完整字段集与正确拼写 'progress'，待真机验证。");

        // M-2 诊断日志：fileupload_list end_time 拼写差异
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                "fileupload_list.end_time",
                "DJI log-upload.html fileupload_list Example 中第二个 list 项将 end_time 误写为 'end_ime'（缺 't'），"
                        + "Column 表为 'end_time'（正确）。模拟器使用 Column 表正确拼写 'end_time'，待真机验证。");

        // M-2 诊断日志：fileupload_list 时间单位差异
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                "fileupload_list.start_time/end_time",
                "DJI log-upload.html fileupload_list Dock1/Dock2 Column 表标注 start_time/end_time 单位为 '秒 / s'，"
                        + "但 Example 值（如 1659427398806）为 13 位毫秒时间戳；Dock3 Column 表标注为 '毫秒 / ms'。"
                        + "模拟器使用毫秒时间戳（与 Example 值和 Dock3 标注一致），待真机验证。");
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    /** 判断 method 是否属于远程日志 Service 指令 */
    public static boolean isRemoteLogServiceMethod(String method) {
        return REMOTE_LOG_SERVICE_METHODS.contains(method);
    }

    /** 处理远程日志 Service 指令，返回 services_reply 的 output */
    public Map<String, Object> handleService(String method, JsonNode data) {
        return switch (method) {
            case "fileupload_start" -> handleFileUploadStart(data);
            case "fileupload_update" -> handleFileUploadUpdate(data);
            case "fileupload_list" -> handleFileUploadList(data);
            default -> throw new IllegalArgumentException("Unsupported remote log service method: " + method);
        };
    }

    /** fileupload_start：解析文件列表，返回 result=0，启动异步进度模拟 */
    private Map<String, Object> handleFileUploadStart(JsonNode data) {
        // 取消已有上传任务
        cancelUploadTask();

        // 解析文件列表
        currentUploadFiles.clear();
        JsonNode filesNode = data.path("params").path("files");
        if (filesNode.isArray()) {
            for (JsonNode fileNode : filesNode) {
                String module = fileNode.path("module").asText();
                String objectKey = fileNode.path("object_key").asText();
                JsonNode listNode = fileNode.path("list");
                if (listNode.isArray()) {
                    for (JsonNode bootNode : listNode) {
                        int bootIndex = bootNode.path("boot_index").asInt();
                        Map<String, Object> fileInfo = new LinkedHashMap<>();
                        fileInfo.put("module", module);
                        fileInfo.put("object_key", objectKey);
                        fileInfo.put("boot_index", bootIndex);
                        fileInfo.put("device_sn", module.equals("0") ? runtimeConfig.getDroneSn() : runtimeConfig.getDockSn());
                        fileInfo.put("key", objectKey + "/boot_" + bootIndex + ".log");
                        fileInfo.put("fingerprint", generateMd5(objectKey + bootIndex));
                        fileInfo.put("size", 155232);  // 默认文件大小
                        currentUploadFiles.add(fileInfo);
                    }
                }
            }
        }

        log.info("fileupload_start: {} 个文件待上传", currentUploadFiles.size());

        // 启动异步进度模拟
        startProgressSimulation();

        return Map.of("result", 0);
    }

    /** fileupload_update：处理上传状态更新（取消），返回 result=0 */
    private Map<String, Object> handleFileUploadUpdate(JsonNode data) {
        String status = data.path("status").asText();
        if ("cancel".equals(status)) {
            cancelUploadTask();
            currentUploadFiles.clear();
            log.info("fileupload_update: 上传已取消");
        }
        return Map.of("result", 0);
    }

    /**
     * fileupload_list：返回设备可上传的日志文件列表。
     * <p>字段对齐 DJI log-upload.html Column 表 + Example：
     * <ul>
     *   <li>data.result: 0（顶层返回码）</li>
     *   <li>data.files[]: 每个模块一个元素，含 device_sn/module/result/list</li>
     *   <li>list[]: 每个日志文件含 boot_index/start_time/end_time/size</li>
     * </ul>
     * <p>注意：DJI Example 中第二个 list 项将 end_time 误写为 end_ime（缺 't'），
     * Column 表为 end_time（正确）。模拟器使用 end_time，记录 M-2 诊断日志。
     *
     * @param data 请求 data，含 module_list 过滤列表（如 ["0","3"]）
     * @return services_reply 的 data，含 result 和 files
     */
    private Map<String, Object> handleFileUploadList(JsonNode data) {
        // 解析 module_list 过滤条件，为空时默认返回全部模块
        List<String> requestedModules = new ArrayList<>();
        JsonNode moduleListNode = data.path("module_list");
        if (moduleListNode.isArray() && !moduleListNode.isEmpty()) {
            for (JsonNode m : moduleListNode) {
                requestedModules.add(m.asText());
            }
        } else {
            requestedModules.add("0");
            requestedModules.add("3");
        }

        long now = System.currentTimeMillis();
        long startTime = now - 86_400_000L;  // 日志开始时间：1 天前
        long endTime = now - 82_800_000L;    // 日志结束时间：23 小时前

        List<Map<String, Object>> files = new ArrayList<>();
        for (String module : requestedModules) {
            boolean isDrone = "0".equals(module);
            String deviceSn = isDrone ? runtimeConfig.getDroneSn() : runtimeConfig.getDockSn();
            int size = isDrone ? MOCK_LOG_SIZE_DRONE : MOCK_LOG_SIZE_DOCK;
            int bootIndexBase = isDrone ? MOCK_BOOT_INDEX_BASE_DRONE : MOCK_BOOT_INDEX_BASE_DOCK;

            List<Map<String, Object>> logList = new ArrayList<>();
            for (int i = 0; i < MOCK_LOG_FILE_COUNT; i++) {
                Map<String, Object> logFile = new LinkedHashMap<>();
                logFile.put("boot_index", bootIndexBase + i);
                logFile.put("start_time", startTime);
                logFile.put("end_time", endTime);  // 使用 Column 表正确拼写（Example 误写为 end_ime）
                logFile.put("size", size);
                logList.add(logFile);
            }

            Map<String, Object> fileGroup = new LinkedHashMap<>();
            fileGroup.put("device_sn", deviceSn);
            fileGroup.put("result", 0);
            fileGroup.put("module", module);
            fileGroup.put("list", logList);
            files.add(fileGroup);
        }

        log.info("fileupload_list: 返回 {} 个模块的日志文件列表", files.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        result.put("result", 0);
        return result;
    }

    /** 启动异步进度模拟：in_progress(50%) → ok(100%) */
    private void startProgressSimulation() {
        if (currentUploadFiles.isEmpty() || !mqtt.isConnected()) {
            return;
        }

        // in_progress（50%）
        uploadTask = scheduler.schedule(() -> {
            publishFileUploadProgress("in_progress", 50);
        }, PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // ok（100%）
        scheduler.schedule(() -> {
            publishFileUploadProgress("ok", 100);
            uploadTask = null;
        }, PROGRESS_INTERVAL_SECONDS * 2, TimeUnit.SECONDS);
    }

    /** 取消当前上传任务 */
    private void cancelUploadTask() {
        if (uploadTask != null) {
            uploadTask.cancel(false);
            uploadTask = null;
        }
    }

    /**
     * 发布 fileupload_progress 事件。
     * <p>字段对齐 DJI log-upload.html Example：
     * <ul>
     *   <li>data.result: 0</li>
     *   <li>data.output.status: in_progress / ok</li>
     *   <li>data.output.ext.files[]: 文件列表，含 module/size/device_sn/key/fingerprint/progress</li>
     *   <li>progress.current_step: 当前步骤</li>
     *   <li>progress.finish_time: 完成时间戳（in_progress 时为 0）</li>
     *   <li>progress.progress: 进度百分比（Column 表误写 'prgress'，使用 Example 正确拼写）</li>
     *   <li>progress.result: 0</li>
     *   <li>progress.status: in_progress / ok（与 output.status 一致）</li>
     *   <li>progress.total_step: 总步骤（仅 dock 模块 module=3 携带）</li>
     *   <li>progress.upload_rate: 上传速率</li>
     *   <li>need_reply=0（单向通知）</li>
     * </ul>
     */
    private void publishFileUploadProgress(String status, int percent) {
        try {
            List<Map<String, Object>> files = new ArrayList<>();
            for (Map<String, Object> fileInfo : currentUploadFiles) {
                String module = String.valueOf(fileInfo.get("module"));
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("module", module);
                file.put("size", fileInfo.get("size"));
                file.put("device_sn", fileInfo.get("device_sn"));
                file.put("key", fileInfo.get("key"));
                file.put("fingerprint", fileInfo.get("fingerprint"));

                // progress 字段对齐 DJI log-upload.html Example：
                // current_step/finish_time/progress/result/status/upload_rate，dock 模块(module=3)额外含 total_step。
                // 注意：Column 表仅列出 prgress/finish_time/upload_rate 且 prgress 拼写错误，Example 字段更完整，以 Example 为准。
                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("current_step", "ok".equals(status) ? TOTAL_STEP : CURRENT_STEP);
                progress.put("finish_time", "ok".equals(status) ? System.currentTimeMillis() : 0);
                progress.put("progress", percent);  // 使用 Example 中的正确拼写（Column 表误写为 'prgress'）
                progress.put("result", 0);
                progress.put("status", status);
                // total_step 仅 dock 模块(module=3)携带，与 DJI Example 一致
                if ("3".equals(module)) {
                    progress.put("total_step", TOTAL_STEP);
                }
                progress.put("upload_rate", UPLOAD_RATE);
                file.put("progress", progress);

                files.add(file);
            }

            Map<String, Object> ext = new LinkedHashMap<>();
            ext.put("files", files);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ext", ext);
            output.put("status", status);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("result", 0);
            data.put("output", output);

            publishEvent("fileupload_progress", data);
            log.info("fileupload_progress 已上报: status={}, percent={}", status, percent);
        } catch (Exception e) {
            log.error("fileupload_progress 上报失败", e);
        }
    }

    /**
     * 手动触发 fileupload_progress 事件（REST API）。
     *
     * @param status  状态（in_progress / ok）
     * @param percent 进度百分比
     * @return 触发结果
     */
    public TriggerResult triggerFileUploadProgress(String status, int percent) {
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报远程日志事件");
        }
        if (currentUploadFiles.isEmpty()) {
            return TriggerResult.fail("NO_FILES", "无待上传文件，请先触发 fileupload_start");
        }
        publishFileUploadProgress(status, percent);
        return TriggerResult.ok();
    }

    private void publishEvent(String method, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("need_reply", 0);
        envelope.put("gateway", runtimeConfig.getDockSn());
        envelope.put("method", method);
        envelope.put("data", data);

        String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
    }

    /** 生成简单 MD5 指纹（模拟） */
    private String generateMd5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "00000000000000000000000000000000";
        }
    }

    // ==================== REST API 辅助 ====================

    /** 查询当前上传文件列表 */
    public List<Map<String, Object>> getCurrentUploadFiles() {
        return new ArrayList<>(currentUploadFiles);
    }

    /** 查询是否有上传任务进行中 */
    public boolean isUploading() {
        return uploadTask != null && !uploadTask.isDone();
    }

    /** 触发结果 */
    public record TriggerResult(boolean success, String code, String message) {
        public static TriggerResult ok() {
            return new TriggerResult(true, null, null);
        }
        public static TriggerResult fail(String code, String message) {
            return new TriggerResult(false, code, message);
        }
    }
}
