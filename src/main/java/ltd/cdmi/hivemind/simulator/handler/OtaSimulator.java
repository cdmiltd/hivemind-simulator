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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 固件升级模拟器（Dock1/Dock2/Dock3 [firmware-upgrade.html]）。
 *
 * <p>处理 DJI Cloud API「固件升级」协议：
 * <ul>
 *   <li>ota_create（Service 下行）：发起固件升级，返回 {result:0, output:{status:"in_progress"}}，异步模拟升级进度</li>
 *   <li>ota_progress（Event 上行）：固件升级进度通知，need_reply=0 单向通知</li>
 * </ul>
 *
 * <p>ota_create 收到后自动模拟升级进度：
 * <ol>
 *   <li>download_firmware 阶段：status=in_progress, percent 递增</li>
 *   <li>upgrade_firmware 阶段：status=in_progress, percent 递增</li>
 *   <li>完成：status=ok</li>
 * </ol>
 * 与 RemoteLogSimulator 异步 Job 模式一致。
 */
@Component
public class OtaSimulator {

    private static final Logger log = LoggerFactory.getLogger(OtaSimulator.class);

    /** 固件升级同步 Service 指令集 */
    private static final Set<String> OTA_SERVICE_METHODS = Set.of("ota_create");

    /** 进度模拟间隔（秒） */
    private static final long PROGRESS_INTERVAL_SECONDS = 2;

    /** download_firmware 阶段模拟进度百分比 */
    private static final int DOWNLOAD_PERCENT = 50;
    /** upgrade_firmware 阶段模拟进度百分比 */
    private static final int UPGRADE_PERCENT = 100;

    private final MqttClientManager mqtt;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    /** 当前升级任务设备列表 */
    private final List<Map<String, Object>> currentUpgradeDevices = new ArrayList<>();

    /** 当前异步升级任务引用（用于取消） */
    private volatile ScheduledFuture<?> upgradeTask;

    private final ScheduledExecutorService scheduler;

    public OtaSimulator(MqttClientManager mqtt, RuntimeConfig runtimeConfig, DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ota-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    /** 判断 method 是否属于固件升级 Service 指令 */
    public static boolean isOtaServiceMethod(String method) {
        return OTA_SERVICE_METHODS.contains(method);
    }

    /** 处理固件升级 Service 指令，返回 services_reply 的 output */
    public Map<String, Object> handleService(String method, JsonNode data) {
        return switch (method) {
            case "ota_create" -> handleOtaCreate(data);
            default -> throw new IllegalArgumentException("Unsupported OTA service method: " + method);
        };
    }

    /**
     * ota_create：解析设备列表，返回 {result:0, output:{status:"in_progress"}}，启动异步进度模拟。
     *
     * <p>请求 data.devices[] 含 sn/product_version/file_url/md5/file_size/file_name/firmware_upgrade_type。
     */
    private Map<String, Object> handleOtaCreate(JsonNode data) {
        // 取消已有升级任务
        cancelUpgradeTask();

        // 解析设备列表
        currentUpgradeDevices.clear();
        JsonNode devicesNode = data.path("devices");
        if (devicesNode.isArray()) {
            for (JsonNode deviceNode : devicesNode) {
                Map<String, Object> device = new LinkedHashMap<>();
                device.put("sn", deviceNode.path("sn").asText());
                device.put("product_version", deviceNode.path("product_version").asText());
                device.put("firmware_upgrade_type", deviceNode.path("firmware_upgrade_type").asInt());
                if (deviceNode.has("file_url")) {
                    device.put("file_url", deviceNode.path("file_url").asText());
                }
                if (deviceNode.has("md5")) {
                    device.put("md5", deviceNode.path("md5").asText());
                }
                if (deviceNode.has("file_size")) {
                    device.put("file_size", deviceNode.path("file_size").asLong());
                }
                if (deviceNode.has("file_name")) {
                    device.put("file_name", deviceNode.path("file_name").asText());
                }
                currentUpgradeDevices.add(device);
            }
        }

        log.info("ota_create: {} 台设备待升级", currentUpgradeDevices.size());

        // 启动异步进度模拟
        startProgressSimulation();

        // 返回 services_reply data
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "in_progress");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", 0);
        result.put("output", output);
        return result;
    }

    /** 启动异步进度模拟：download_firmware(50%) → upgrade_firmware(100%) → ok */
    private void startProgressSimulation() {
        if (currentUpgradeDevices.isEmpty() || !mqtt.isConnected()) {
            return;
        }

        // download_firmware 阶段（50%）
        upgradeTask = scheduler.schedule(() -> {
            publishOtaProgress("in_progress", "download_firmware", DOWNLOAD_PERCENT);
        }, PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // upgrade_firmware 阶段（100%）
        scheduler.schedule(() -> {
            publishOtaProgress("in_progress", "upgrade_firmware", UPGRADE_PERCENT);
        }, PROGRESS_INTERVAL_SECONDS * 2, TimeUnit.SECONDS);

        // 完成（ok）
        scheduler.schedule(() -> {
            publishOtaProgress("ok", "upgrade_firmware", UPGRADE_PERCENT);
            upgradeTask = null;
        }, PROGRESS_INTERVAL_SECONDS * 3, TimeUnit.SECONDS);
    }

    /** 取消当前升级任务 */
    private void cancelUpgradeTask() {
        if (upgradeTask != null) {
            upgradeTask.cancel(false);
            upgradeTask = null;
        }
    }

    /**
     * 发布 ota_progress 事件。
     * <p>字段对齐 DJI firmware-upgrade.html：
     * <ul>
     *   <li>data.result: 0</li>
     *   <li>data.output.status: in_progress / ok / failed / canceled / paused / rejected / sent / timeout</li>
     *   <li>data.output.progress.percent: 0-100</li>
     *   <li>data.output.progress.current_step: download_firmware / upgrade_firmware</li>
     *   <li>need_reply=0（单向通知）</li>
     * </ul>
     */
    private void publishOtaProgress(String status, String currentStep, int percent) {
        try {
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("percent", percent);
            progress.put("current_step", currentStep);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", status);
            output.put("progress", progress);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("result", 0);
            data.put("output", output);

            publishEvent("ota_progress", data);
            log.info("ota_progress 已上报: status={}, current_step={}, percent={}", status, currentStep, percent);
        } catch (Exception e) {
            log.error("ota_progress 上报失败", e);
        }
    }

    /**
     * 手动触发 ota_progress 事件（REST API）。
     *
     * @param status      任务状态
     * @param currentStep 当前步骤（download_firmware / upgrade_firmware）
     * @param percent     进度百分比
     * @return 触发结果
     */
    public TriggerResult triggerOtaProgress(String status, String currentStep, int percent) {
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报固件升级事件");
        }
        if (currentUpgradeDevices.isEmpty()) {
            return TriggerResult.fail("NO_TASK", "无升级任务，请先触发 ota_create");
        }
        publishOtaProgress(status, currentStep, percent);
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

    // ==================== REST API 辅助 ====================

    /** 查询当前升级设备列表 */
    public List<Map<String, Object>> getCurrentUpgradeDevices() {
        return new ArrayList<>(currentUpgradeDevices);
    }

    /** 查询是否有升级任务进行中 */
    public boolean isUpgrading() {
        return upgradeTask != null && !upgradeTask.isDone();
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
