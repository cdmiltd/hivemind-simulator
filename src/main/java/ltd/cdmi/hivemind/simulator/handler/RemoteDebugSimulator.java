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
import jakarta.annotation.PreDestroy;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.TopicConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 远程调试模拟器（cmd.html）。
 * <p>处理 DJI Cloud API「远程调试」协议，分为两类：
 * <ul>
 *   <li>同步指令（Cmd）：仅回 services_reply(result=0)，无进度事件</li>
 *   <li>异步任务（Job）：services_reply(result=0) + events 进度事件（in_progress→ok + percent）+ DeviceState 状态同步</li>
 * </ul>
 * <p>区分 Dock1/Dock2/Dock3 指令集差异：
 * <ul>
 *   <li>Dock1 独有 Job：putter_open / putter_close</li>
 *   <li>Dock2+3 共有 Job：esim_activate / esim_operator_switch；Cmd：sim_slot_switch</li>
 *   <li>Dock3 独有 Job：rtk_calibration</li>
 * </ul>
 * <p>详见 DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/cmd.html">远程调试（Dock3）</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/cmd.html">Dock2</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/cmd.html">Dock1</a>。
 */
@Component
public class RemoteDebugSimulator {

    private static final Logger log = LoggerFactory.getLogger(RemoteDebugSimulator.class);

    /** 进度事件间隔（秒） */
    private static final long PROGRESS_INTERVAL_SECONDS = 1;

    // ==================== 三 Dock 共有指令 ====================

    /** 三 Dock 共有 Job 指令（需进度事件） */
    private static final Set<String> COMMON_JOB_METHODS = Set.of(
            "cover_open", "cover_close", "cover_force_close",
            "drone_open", "drone_close",
            "charge_open", "charge_close",
            "device_reboot", "device_format", "drone_format"
    );

    /** 三 Dock 共有 Cmd 指令（仅 services_reply） */
    private static final Set<String> COMMON_CMD_METHODS = Set.of(
            "debug_mode_open", "debug_mode_close",
            "supplement_light_open", "supplement_light_close",
            "battery_maintenance_switch", "battery_store_mode_switch",
            "alarm_state_switch", "air_conditioner_mode_switch",
            "sdr_workmode_switch"
    );

    // ==================== Dock 特有指令 ====================

    /** Dock1 独有 Job 指令 */
    private static final Set<String> DOCK1_JOB_METHODS = Set.of(
            "putter_open", "putter_close"
    );

    /** Dock2/Dock3 共有 Job 指令 */
    private static final Set<String> DOCK2_3_JOB_METHODS = Set.of(
            "esim_activate", "esim_operator_switch"
    );

    /** Dock2/Dock3 共有 Cmd 指令 */
    private static final Set<String> DOCK2_3_CMD_METHODS = Set.of(
            "sim_slot_switch"
    );

    /** Dock3 独有 Job 指令 */
    private static final Set<String> DOCK3_JOB_METHODS = Set.of(
            "rtk_calibration"
    );

    // ==================== 所有远程调试指令（供 ServiceCommandHandler 路由判断） ====================

    /** 所有远程调试指令集合 */
    private static final Set<String> ALL_REMOTE_DEBUG_METHODS = combineAllMethods();

    private static Set<String> combineAllMethods() {
        Set<String> all = new java.util.HashSet<>();
        all.addAll(COMMON_JOB_METHODS);
        all.addAll(COMMON_CMD_METHODS);
        all.addAll(DOCK1_JOB_METHODS);
        all.addAll(DOCK2_3_JOB_METHODS);
        all.addAll(DOCK2_3_CMD_METHODS);
        all.addAll(DOCK3_JOB_METHODS);
        return java.util.Collections.unmodifiableSet(all);
    }

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final RuntimeConfig runtimeConfig;
    private final ScheduledExecutorService scheduler;

    public RemoteDebugSimulator(SimulatorProperties props, MqttClientManager mqtt,
                                 DeviceState state, RuntimeConfig runtimeConfig) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.runtimeConfig = runtimeConfig;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "remote-debug-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    /**
     * 判断 method 是否属于远程调试指令。
     * 供 ServiceCommandHandler 路由判断使用。
     */
    public static boolean isRemoteDebugMethod(String method) {
        return ALL_REMOTE_DEBUG_METHODS.contains(method);
    }

    /**
     * 统一路由远程调试指令（由 ServiceCommandHandler 调用）。
     * @param method 指令方法名
     * @param data   指令 data（当前未使用，预留参数解析）
     * @param bid    原始 services 指令的 bid（进度事件需保持一致）
     * @return services_reply 的 output（含 result 字段）
     */
    public Map<String, Object> handle(String method, JsonNode data, String bid) {
        DeviceType dockType = runtimeConfig.getDockType();

        // 判断是否为当前 Dock 类型支持的 Job 指令
        if (isJobMethodSupported(method, dockType)) {
            return handleJob(method, bid, dockType);
        }

        // 判断是否为当前 Dock 类型支持的 Cmd 指令
        if (isCmdMethodSupported(method, dockType)) {
            return handleCmd(method);
        }

        // 不属于当前 Dock 类型的指令（如 Dock2 收到 putter_open）：占位应答，不发进度事件
        log.warn("远程调试指令 {} 不属于当前 Dock 类型 {}，返回占位 result=0", method, dockType);
        return Map.of("result", 0);
    }

    // ==================== Job 指令处理（异步双阶段确认） ====================

    /**
     * 处理 Job 指令：回 result=0 + 调度进度事件 + 状态同步。
     */
    private Map<String, Object> handleJob(String method, String bid, DeviceType dockType) {
        log.info("远程调试 Job 指令: method={}, bid={}, dockType={}", method, bid, dockType);
        scheduleProgressEvent(method, bid);
        return Map.of("result", 0);
    }

    /**
     * 调度进度事件序列：in_progress(percent=50) → ok(percent=100)。
     * <p>bid 与原始 services 指令一致，hivemind 据此置 ACK=SUCCESS。</p>
     */
    private void scheduleProgressEvent(String method, String bid) {
        // in_progress（执行中）
        scheduler.schedule(() -> {
            publishProgressEvent(method, bid, "in_progress", 50);
        }, PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // ok（完成）+ 状态同步
        scheduler.schedule(() -> {
            publishProgressEvent(method, bid, "ok", 100);
            syncDeviceState(method);
        }, PROGRESS_INTERVAL_SECONDS * 2, TimeUnit.SECONDS);
    }

    /**
     * 发布进度事件。
     * <p>格式：{@code {bid, data:{result, output:{status, progress:{percent}}}, tid, timestamp, method}}</p>
     */
    private void publishProgressEvent(String method, String bid, String status, int percent) {
        try {
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("percent", percent);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", status);
            output.put("progress", progress);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("result", 0);
            data.put("output", output);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("bid", bid);
            envelope.put("data", data);
            envelope.put("tid", UUID.randomUUID().toString());
            envelope.put("timestamp", System.currentTimeMillis());
            envelope.put("method", method);

            String topic = TopicConstants.topic(TopicConstants.EVENTS, runtimeConfig.getDockSn());
            mqtt.publishJson(topic, envelope);
            log.info("远程调试进度事件: method={}, bid={}, status={}, percent={}", method, bid, status, percent);
        } catch (Exception e) {
            log.error("发布远程调试进度事件失败: method={}, bid={}, err={}", method, bid, e.getMessage(), e);
        }
    }

    /**
     * 同步 DeviceState（Job 完成后调用）。
     * <p>仅对有明确状态映射的指令进行同步，无映射的指令（如 device_reboot）不修改状态。</p>
     */
    private void syncDeviceState(String method) {
        switch (method) {
            case "cover_open" -> state.setCoverOpen(true);
            case "cover_close", "cover_force_close" -> state.setCoverOpen(false);
            case "drone_open" -> state.setDroneInDock(false);
            case "drone_close" -> state.setDroneInDock(true);
            case "charge_open" -> state.setDroneChargeState(1);
            case "charge_close" -> state.setDroneChargeState(0);
            case "putter_open" -> state.setPutterExpanded(true);
            case "putter_close" -> state.setPutterExpanded(false);
            default -> { /* device_reboot, device_format, drone_format, esim_activate, esim_operator_switch, rtk_calibration 无状态变更 */ }
        }
        log.info("远程调试状态同步: method={} → {}", method, stateSnapshot(method));
    }

    private String stateSnapshot(String method) {
        return switch (method) {
            case "cover_open", "cover_close", "cover_force_close" -> "coverOpen=" + state.isCoverOpen();
            case "drone_open", "drone_close" -> "droneInDock=" + state.isDroneInDock();
            case "charge_open", "charge_close" -> "droneChargeState=" + state.getDroneChargeState();
            case "putter_open", "putter_close" -> "putterExpanded=" + state.isPutterExpanded();
            default -> "无状态变更";
        };
    }

    // ==================== Cmd 指令处理（同步，无进度事件） ====================

    /**
     * 处理 Cmd 指令：仅回 result=0，无进度事件。
     */
    private Map<String, Object> handleCmd(String method) {
        log.info("远程调试 Cmd 指令: method={}（同步应答，无进度事件）", method);
        return Map.of("result", 0);
    }

    // ==================== Dock 类型支持判断 ====================

    /**
     * 判断 method 是否为当前 Dock 类型支持的 Job 指令。
     */
    private boolean isJobMethodSupported(String method, DeviceType dockType) {
        if (COMMON_JOB_METHODS.contains(method)) {
            return true;
        }
        return switch (dockType) {
            case DOCK1 -> DOCK1_JOB_METHODS.contains(method);
            case DOCK2 -> DOCK2_3_JOB_METHODS.contains(method);
            case DOCK3 -> DOCK2_3_JOB_METHODS.contains(method) || DOCK3_JOB_METHODS.contains(method);
            default -> false;
        };
    }

    /**
     * 判断 method 是否为当前 Dock 类型支持的 Cmd 指令。
     */
    private boolean isCmdMethodSupported(String method, DeviceType dockType) {
        if (COMMON_CMD_METHODS.contains(method)) {
            return true;
        }
        return switch (dockType) {
            case DOCK1 -> false;
            case DOCK2, DOCK3 -> DOCK2_3_CMD_METHODS.contains(method);
            default -> false;
        };
    }
}
