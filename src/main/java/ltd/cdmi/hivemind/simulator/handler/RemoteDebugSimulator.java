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
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * services_reply 无 output.status 字段的 Cmd 指令集合。
     * <p>DJI 文档核实：sdr_workmode_switch/sim_slot_switch 的 services_reply 仅有 result，无 output。
     * 其他 Cmd 指令（debug_mode_open/battery_maintenance_switch 等）有 output.status。</p>
     */
    private static final Set<String> CMD_METHODS_WITHOUT_OUTPUT = Set.of(
            "sdr_workmode_switch",
            "sim_slot_switch"
    );

    /**
     * services_reply 无 output.status 字段的 Job 指令集合。
     * <p>DJI 文档核实：esim_operator_switch 的 services_reply 仅有 result，无 output。
     * 其他 Job 指令（cover_open/drone_open 等）有 output.status="sent"。</p>
     */
    private static final Set<String> JOB_METHODS_WITHOUT_OUTPUT = Set.of(
            "esim_operator_switch"
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

    // ==================== 进度事件 step_key 映射（对齐 DJI cmd 文档） ====================

    /**
     * 进度事件中 output.progress.step_key 的映射。
     * <p>仅包含 DJI 文档明确要求 step_key 字段的指令，值使用文档枚举中的具体步骤名：
     * <ul>
     *   <li>cover_open → "open_cover"（DJI Dock3 cmd 文档例子）</li>
     *   <li>cover_close → "close_cover"（DJI 文档枚举的最终步骤）</li>
     *   <li>drone_close → "close_drone"（DJI 文档枚举的最终步骤）</li>
     *   <li>device_reboot → "write_reboot_param_file"（DJI 文档枚举的最终步骤）</li>
     *   <li>charge_open/charge_close/putter_open/putter_close → "get_bid"（占位，无完整文档枚举）</li>
     * </ul>
     * 无 step_key 的指令（cover_force_close/device_format/drone_format/esim_activate/esim_operator_switch）不在此映射中。
     * drone_open 无 progress 字段，也不在此映射中（由 publishProgressEvent 特殊处理）。
     * rtk_calibration 使用 current_step（int），也不在此映射中（由 publishProgressEvent 特殊处理）。</p>
     */
    private static final Map<String, String> STEP_KEYS = Map.of(
            "cover_open", "open_cover",
            "cover_close", "close_cover",
            "drone_close", "close_drone",
            "device_reboot", "write_reboot_param_file",
            "charge_open", "get_bid",
            "charge_close", "get_bid",
            "putter_open", "get_bid",
            "putter_close", "get_bid"
    );

    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;
    private final ScheduledExecutorService scheduler;

    public RemoteDebugSimulator(MqttClientManager mqtt,
                                 DeviceState state, RuntimeConfig runtimeConfig,
                                 DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.state = state;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
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

        // 不属于当前 Dock 类型的指令（如 Dock2 收到 putter_open）：返回 rejected，不发进度事件
        log.warn("远程调试指令 {} 不属于当前 Dock 类型 {}，返回 rejected", method, dockType);
        Map<String, Object> rejectOutput = new LinkedHashMap<>();
        rejectOutput.put("status", "rejected");
        return Map.of("result", 0, "output", rejectOutput);
    }

    // ==================== Job 指令处理（异步双阶段确认） ====================

    /**
     * 处理 Job 指令：回 result=0 + 调度进度事件 + 状态同步。
     * <p>DJI 文档：大部分 Job 指令的 services_reply 有 output.status="sent"（已下发），
     * 但部分指令（esim_operator_switch）的 services_reply 仅有 result，无 output。</p>
     */
    private Map<String, Object> handleJob(String method, String bid, DeviceType dockType) {
        log.info("远程调试 Job 指令: method={}, bid={}, dockType={}", method, bid, dockType);
        scheduleProgressEvent(method, bid);
        // esim_operator_switch 等指令的 services_reply 仅有 result，无 output（DJI 文档明确）
        if (JOB_METHODS_WITHOUT_OUTPUT.contains(method)) {
            return Map.of("result", 0);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "sent");
        return Map.of("result", 0, "output", output);
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
     * <p>字段对齐 DJI 远程调试文档（Dock1/Dock2/Dock3 cmd.html）：
     * <ul>
     *   <li>通用结构：{@code data.{result, output:{status, progress:{percent, step_key?}}}}</li>
     *   <li>step_key 仅特定指令有（cover_open/cover_close/drone_close/device_reboot/charge_open/charge_close/putter_open/putter_close），使用文档中的具体步骤名</li>
     *   <li>drone_open 特殊：无 progress 字段，仅有 output.status（DJI 文档明确）</li>
     *   <li>cover_force_close/device_format/drone_format/esim_activate/esim_operator_switch：有 progress 但无 step_key</li>
     *   <li>rtk_calibration 特殊：ext.devices 数组 + progress.current_step（int，非 step_key）+ need_reply=1（DJI Dock3 cmd 文档明确）</li>
     * </ul>
     * </p>
     */
    private void publishProgressEvent(String method, String bid, String status, int percent) {
        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", status);

            if ("rtk_calibration".equals(method)) {
                // rtk_calibration 特殊结构（DJI Dock3 cmd 文档）：
                // ext.devices 数组 + progress.current_step（int，非 step_key）
                output.put("ext", buildRtkCalibrationExt(status));
                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("percent", percent);
                progress.put("current_step", 1);
                output.put("progress", progress);
            } else if (!"drone_open".equals(method)) {
                // 通用结构：progress.percent + step_key?
                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("percent", percent);
                String stepKey = STEP_KEYS.get(method);
                if (stepKey != null) {
                    progress.put("step_key", stepKey);
                }
                output.put("progress", progress);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("result", 0);
            data.put("output", output);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("bid", bid);
            envelope.put("data", data);
            envelope.put("tid", UUID.randomUUID().toString());
            envelope.put("timestamp", System.currentTimeMillis());
            // DJI topic-definition 文档：need_reply 是 events 结构的字段，所有 events 都应包含
            // rtk_calibration 需要 need_reply=1（DJI Dock3 cmd 文档明确），通用指令 need_reply=0
            envelope.put("need_reply", "rtk_calibration".equals(method) ? 1 : 0);
            // DJI topic-definition 文档：gateway 是公共字段，所有消息都应包含
            envelope.put("gateway", runtimeConfig.getDockSn());
            envelope.put("method", method);

            String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getDockSn());
            mqtt.publishJson(topic, envelope);
            log.info("远程调试进度事件: method={}, bid={}, status={}, percent={}", method, bid, status, percent);
        } catch (Exception e) {
            log.error("发布远程调试进度事件失败: method={}, bid={}, err={}", method, bid, e.getMessage(), e);
        }
    }

    /**
     * 构造 rtk_calibration 的 ext 结构（DJI Dock3 cmd 文档）。
     * <p>ext.devices 数组包含 RTK 模块的标定结果：
     * <ul>
     *   <li>sn: 设备 SN（飞行器 SN）</li>
     *   <li>type: 设备类型（1=飞行器）</li>
     *   <li>module: 模块类型（"3"=RTK 主模块）</li>
     *   <li>result: 标定结果（0=成功）</li>
     *   <li>status: 状态（"ok"/"in_progress"/"failed"）</li>
     * </ul>
     * <p>模拟器默认标定成功（单个 RTK 主模块，result=0）。</p>
     */
    private Map<String, Object> buildRtkCalibrationExt(String status) {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("sn", runtimeConfig.getDroneSn());
        device.put("type", 1);
        device.put("module", "3");
        device.put("result", 0);
        device.put("status", "ok".equals(status) ? "ok" : "in_progress");

        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("devices", List.of(device));
        ext.put("version", 1);
        return ext;
    }

    /**
     * 同步 DeviceState（Job 完成后调用）。
     * <p>仅对有明确状态映射的指令进行同步，无映射的指令（如 device_reboot）不修改状态。</p>
     * <p>语义对齐 DJI 远程调试文档：
     * <ul>
     *   <li>drone_open = 飞行器开机 → droneActivated=true（开始推送 drone OSD）</li>
     *   <li>drone_close = 飞行器关机 → droneActivated=false（停止推送 drone OSD）</li>
     * </ul>
     * </p>
     */
    private void syncDeviceState(String method) {
        switch (method) {
            case "cover_open" -> state.setCoverOpen(true);
            case "cover_close", "cover_force_close" -> state.setCoverOpen(false);
            case "drone_open" -> state.setDroneActivated(true);
            case "drone_close" -> state.setDroneActivated(false);
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
            case "drone_open", "drone_close" -> "droneActivated=" + state.isDroneActivated();
            case "charge_open", "charge_close" -> "droneChargeState=" + state.getDroneChargeState();
            case "putter_open", "putter_close" -> "putterExpanded=" + state.isPutterExpanded();
            default -> "无状态变更";
        };
    }

    // ==================== Cmd 指令处理（同步，无进度事件） ====================

    /**
     * 处理 Cmd 指令：回 result=0，无进度事件。
     * <p>DJI 文档：Cmd 指令是同步指令，services_reply 直接返回最终结果。
     * 大部分 Cmd 指令有 output.status="ok"（执行成功），但部分指令（sdr_workmode_switch）
     * 的 services_reply 仅有 result，无 output。</p>
     */
    private Map<String, Object> handleCmd(String method) {
        log.info("远程调试 Cmd 指令: method={}（同步应答，无进度事件）", method);
        // sdr_workmode_switch 等指令的 services_reply 仅有 result，无 output（DJI 文档明确）
        if (CMD_METHODS_WITHOUT_OUTPUT.contains(method)) {
            return Map.of("result", 0);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "ok");
        return Map.of("result", 0, "output", output);
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
