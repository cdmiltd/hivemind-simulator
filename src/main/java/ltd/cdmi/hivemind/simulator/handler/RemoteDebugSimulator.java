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
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.codec.MessageCodec;
import ltd.cdmi.dji.cloudapi.sdk.command.service.debug.AirConditionerModeSwitchRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.debug.AlarmStateSwitchRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.debug.BatteryStoreModeSwitchRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.debug.RtkCalibrationRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.debug.SdrWorkmodeSwitchRequest;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.ServiceMethod;
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
            ServiceMethod.COVER_OPEN.methodName(), ServiceMethod.COVER_CLOSE.methodName(), ServiceMethod.COVER_FORCE_CLOSE.methodName(),
            ServiceMethod.DRONE_OPEN.methodName(), ServiceMethod.DRONE_CLOSE.methodName(),
            ServiceMethod.CHARGE_OPEN.methodName(), ServiceMethod.CHARGE_CLOSE.methodName(),
            ServiceMethod.DEVICE_REBOOT.methodName(), ServiceMethod.DEVICE_FORMAT.methodName(), ServiceMethod.DRONE_FORMAT.methodName()
    );

    /** 三 Dock 共有 Cmd 指令（仅 services_reply） */
    private static final Set<String> COMMON_CMD_METHODS = Set.of(
            ServiceMethod.DEBUG_MODE_OPEN.methodName(), ServiceMethod.DEBUG_MODE_CLOSE.methodName(),
            ServiceMethod.SUPPLEMENT_LIGHT_OPEN.methodName(), ServiceMethod.SUPPLEMENT_LIGHT_CLOSE.methodName(),
            ServiceMethod.BATTERY_MAINTENANCE_SWITCH.methodName(), ServiceMethod.BATTERY_STORE_MODE_SWITCH.methodName(),
            ServiceMethod.ALARM_STATE_SWITCH.methodName(), ServiceMethod.AIR_CONDITIONER_MODE_SWITCH.methodName(),
            ServiceMethod.SDR_WORKMODE_SWITCH.methodName()
    );

    // ==================== Dock 特有指令 ====================

    /** Dock1 独有 Job 指令 */
    private static final Set<String> DOCK1_JOB_METHODS = Set.of(
            ServiceMethod.PUTTER_OPEN.methodName(), ServiceMethod.PUTTER_CLOSE.methodName()
    );

    /** Dock2/Dock3 共有 Job 指令 */
    private static final Set<String> DOCK2_3_JOB_METHODS = Set.of(
            ServiceMethod.ESIM_ACTIVATE.methodName(), ServiceMethod.ESIM_OPERATOR_SWITCH.methodName()
    );

    /** Dock2/Dock3 共有 Cmd 指令 */
    private static final Set<String> DOCK2_3_CMD_METHODS = Set.of(
            ServiceMethod.SIM_SLOT_SWITCH.methodName()
    );

    /** Dock3 独有 Job 指令 */
    private static final Set<String> DOCK3_JOB_METHODS = Set.of(
            ServiceMethod.RTK_CALIBRATION.methodName()
    );

    /**
     * services_reply 无 output.status 字段的 Cmd 指令集合。
     * <p>DJI 文档核实：sdr_workmode_switch/sim_slot_switch 的 services_reply 仅有 result，无 output。
     * 其他 Cmd 指令（debug_mode_open/battery_maintenance_switch 等）有 output.status。</p>
     */
    private static final Set<String> CMD_METHODS_WITHOUT_OUTPUT = Set.of(
            ServiceMethod.SDR_WORKMODE_SWITCH.methodName(),
            ServiceMethod.SIM_SLOT_SWITCH.methodName()
    );

    /**
     * services_reply 无 output.status 字段的 Job 指令集合。
     * <p>DJI 文档核实：esim_operator_switch 的 services_reply 仅有 result，无 output。
     * 其他 Job 指令（cover_open/drone_open 等）有 output.status="sent"。</p>
     */
    private static final Set<String> JOB_METHODS_WITHOUT_OUTPUT = Set.of(
            ServiceMethod.ESIM_OPERATOR_SWITCH.methodName()
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
            ServiceMethod.COVER_OPEN.methodName(), "open_cover",
            ServiceMethod.COVER_CLOSE.methodName(), "close_cover",
            ServiceMethod.DRONE_CLOSE.methodName(), "close_drone",
            ServiceMethod.DEVICE_REBOOT.methodName(), "write_reboot_param_file",
            ServiceMethod.CHARGE_OPEN.methodName(), "get_bid",
            ServiceMethod.CHARGE_CLOSE.methodName(), "get_bid",
            ServiceMethod.PUTTER_OPEN.methodName(), "get_bid",
            ServiceMethod.PUTTER_CLOSE.methodName(), "get_bid"
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
     * @param data   指令 data（对有 SDK POJO 的指令反序列化为 POJO 并记录请求字段，见 {@link #logServiceRequest}；无 POJO 的指令不解析）
     * @param bid    原始 services 指令的 bid（进度事件需保持一致）
     * @return services_reply 的 output（含 result 字段）
     */
    public Map<String, Object> handle(String method, JsonNode data, String bid) {
        DockModel dockType = runtimeConfig.getDockType();

        // 判断是否为当前 Dock 类型支持的 Job 指令
        if (isJobMethodSupported(method, dockType)) {
            // DJI 文档：cover_open 等 Job 指令有 check_work_mode 步骤，需先进入远程调试模式
            // 未进入调试模式时返回 failed，模拟真机的 check_work_mode 检查失败
            if (!state.isDebugMode()) {
                log.warn("[M-2] 远程调试 Job 指令 {} 被拒绝：设备未进入远程调试模式（check_work_mode 失败）", method);
                Map<String, Object> failedOutput = new LinkedHashMap<>();
                failedOutput.put("status", "failed");
                return Map.of("result", 0, "output", failedOutput);
            }
            return handleJob(method, data, bid, dockType);
        }

        // 判断是否为当前 Dock 类型支持的 Cmd 指令
        if (isCmdMethodSupported(method, dockType)) {
            return handleCmd(method, data);
        }

        // 不属于当前 Dock 类型的指令（如 Dock2 收到 putter_open）：返回 rejected，不发进度事件
        log.warn("远程调试指令 {} 不属于当前 Dock 类型 {}，返回 rejected", method, dockType);
        Map<String, Object> rejectOutput = new LinkedHashMap<>();
        rejectOutput.put("status", "rejected");
        return Map.of("result", 0, "output", rejectOutput);
    }

    // ==================== 请求解析（SDK POJO，仅覆盖有 POJO 的指令） ====================

    /**
     * 解析 services 请求 data 并记录请求字段（仅对有 SDK POJO 的指令）。
     * <p>对齐项目其他 Simulator 的 SDK POJO 解析模式：使用 {@link MessageCodec#fromJson}
     * 将 data 反序列化为 SDK POJO，通过 record 访问器读取字段并记录日志。无对应 SDK POJO
     * 的指令（cover_open/drone_open/debug_mode_open/supplement_light_open/
     * battery_maintenance_switch/putter_open/esim_activate/esim_operator_switch/
     * sim_slot_switch 等）保留原有行为——不解析请求参数。</p>
     * <p>SDK POJO 覆盖的指令：air_conditioner_mode_switch、alarm_state_switch、
     * battery_store_mode_switch、rtk_calibration、sdr_workmode_switch。</p>
     */
    private void logServiceRequest(String method, JsonNode data) {
        // data 可能为 null（如 rtk_calibration 在测试中以 data=null 调用），直接返回避免 NPE
        if (data == null || data.isMissingNode()) {
            return;
        }
        if (ServiceMethod.AIR_CONDITIONER_MODE_SWITCH.methodName().equals(method)) {
            var req = MessageCodec.fromJson(data.toString(), AirConditionerModeSwitchRequest.class);
            log.info("air_conditioner_mode_switch 请求: mode={}", req.mode());
        } else if (ServiceMethod.ALARM_STATE_SWITCH.methodName().equals(method)) {
            var req = MessageCodec.fromJson(data.toString(), AlarmStateSwitchRequest.class);
            log.info("alarm_state_switch 请求: action={}", req.action());
        } else if (ServiceMethod.BATTERY_STORE_MODE_SWITCH.methodName().equals(method)) {
            var req = MessageCodec.fromJson(data.toString(), BatteryStoreModeSwitchRequest.class);
            log.info("battery_store_mode_switch 请求: mode={}", req.mode());
        } else if (ServiceMethod.RTK_CALIBRATION.methodName().equals(method)) {
            var req = MessageCodec.fromJson(data.toString(), RtkCalibrationRequest.class);
            log.info("rtk_calibration 请求: cali_type={}", req.caliType());
        } else if (ServiceMethod.SDR_WORKMODE_SWITCH.methodName().equals(method)) {
            var req = MessageCodec.fromJson(data.toString(), SdrWorkmodeSwitchRequest.class);
            log.info("sdr_workmode_switch 请求: link_workmode={}", req.linkWorkmode());
        }
        // 无对应 SDK POJO 的指令，保留原行为（不解析请求参数）
    }

    // ==================== Job 指令处理（异步双阶段确认） ====================

    /**
     * 处理 Job 指令：回 result=0 + 调度进度事件 + 状态同步。
     * <p>DJI 文档：大部分 Job 指令的 services_reply 有 output.status="sent"（已下发），
     * 但部分指令（esim_operator_switch）的 services_reply 仅有 result，无 output。</p>
     */
    private Map<String, Object> handleJob(String method, JsonNode data, String bid, DockModel dockType) {
        log.info("远程调试 Job 指令: method={}, bid={}, dockType={}", method, bid, dockType);
        logServiceRequest(method, data);
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

            if (ServiceMethod.RTK_CALIBRATION.methodName().equals(method)) {
                // rtk_calibration 特殊结构（DJI Dock3 cmd 文档）：
                // ext.devices 数组 + progress.current_step（int，非 step_key）
                output.put("ext", buildRtkCalibrationExt(status));
                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("percent", percent);
                progress.put("current_step", 1);
                output.put("progress", progress);
            } else if (!ServiceMethod.DRONE_OPEN.methodName().equals(method)) {
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
            envelope.put("need_reply", ServiceMethod.RTK_CALIBRATION.methodName().equals(method) ? 1 : 0);
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
        if (ServiceMethod.COVER_OPEN.methodName().equals(method)) {
            state.setCoverOpen(true);
        } else if (ServiceMethod.COVER_CLOSE.methodName().equals(method)
                || ServiceMethod.COVER_FORCE_CLOSE.methodName().equals(method)) {
            state.setCoverOpen(false);
        } else if (ServiceMethod.DRONE_OPEN.methodName().equals(method)) {
            state.setDroneActivated(true);
        } else if (ServiceMethod.DRONE_CLOSE.methodName().equals(method)) {
            state.setDroneActivated(false);
        } else if (ServiceMethod.CHARGE_OPEN.methodName().equals(method)) {
            state.setDroneChargeState(1);
        } else if (ServiceMethod.CHARGE_CLOSE.methodName().equals(method)) {
            state.setDroneChargeState(0);
        } else if (ServiceMethod.PUTTER_OPEN.methodName().equals(method)) {
            state.setPutterExpanded(true);
        } else if (ServiceMethod.PUTTER_CLOSE.methodName().equals(method)) {
            state.setPutterExpanded(false);
        }
        // device_reboot, device_format, drone_format, esim_activate, esim_operator_switch, rtk_calibration 无状态变更
        log.info("远程调试状态同步: method={} → {}", method, stateSnapshot(method));
    }

    private String stateSnapshot(String method) {
        if (ServiceMethod.COVER_OPEN.methodName().equals(method)
                || ServiceMethod.COVER_CLOSE.methodName().equals(method)
                || ServiceMethod.COVER_FORCE_CLOSE.methodName().equals(method)) {
            return "coverOpen=" + state.isCoverOpen();
        }
        if (ServiceMethod.DRONE_OPEN.methodName().equals(method)
                || ServiceMethod.DRONE_CLOSE.methodName().equals(method)) {
            return "droneActivated=" + state.isDroneActivated();
        }
        if (ServiceMethod.CHARGE_OPEN.methodName().equals(method)
                || ServiceMethod.CHARGE_CLOSE.methodName().equals(method)) {
            return "droneChargeState=" + state.getDroneChargeState();
        }
        if (ServiceMethod.PUTTER_OPEN.methodName().equals(method)
                || ServiceMethod.PUTTER_CLOSE.methodName().equals(method)) {
            return "putterExpanded=" + state.isPutterExpanded();
        }
        return "无状态变更";
    }

    // ==================== Cmd 指令处理（同步，无进度事件） ====================

    /**
     * 处理 Cmd 指令：回 result=0，无进度事件。
     * <p>DJI 文档：Cmd 指令是同步指令，services_reply 直接返回最终结果。
     * 大部分 Cmd 指令有 output.status="ok"（执行成功），但部分指令（sdr_workmode_switch）
     * 的 services_reply 仅有 result，无 output。</p>
     */
    private Map<String, Object> handleCmd(String method, JsonNode data) {
        log.info("远程调试 Cmd 指令: method={}（同步应答，无进度事件）", method);
        logServiceRequest(method, data);
        // debug_mode_open/close：切换远程调试模式状态（TC-RD-013）
        // 联动 dockModeCode：机场 OSD mode_code 是平台展示机场状态的唯一字段，
        // 只设内部 debugMode 标志会导致进入调试模式后 OSD 仍显示"空闲中"
        if (ServiceMethod.DEBUG_MODE_OPEN.methodName().equals(method)) {
            state.setDebugMode(true);
            state.setDockModeCode(2);  // DockModeCode.REMOTE_DEBUG：远程调试
            log.info("设备已进入远程调试模式（debug_mode=true, mode_code=2）");
        } else if (ServiceMethod.DEBUG_MODE_CLOSE.methodName().equals(method)) {
            state.setDebugMode(false);
            state.setDockModeCode(0);  // DockModeCode.IDLE：空闲中
            log.info("设备已退出远程调试模式（debug_mode=false, mode_code=0）");
        }
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
    private boolean isJobMethodSupported(String method, DockModel dockType) {
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
    private boolean isCmdMethodSupported(String method, DockModel dockType) {
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
