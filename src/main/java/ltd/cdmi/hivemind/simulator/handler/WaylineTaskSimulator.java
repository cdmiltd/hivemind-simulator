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
import jakarta.annotation.PreDestroy;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 航线任务模拟器。
 * <p>处理 flighttask_prepare / flighttask_execute / flighttask_pause / flighttask_recovery /
 * flighttask_undo / flighttask_stop / return_home / return_specific_home / flight_setup_abort 等命令，
 * 并按时间推进上报 flighttask_progress 事件。</p>
 * <p>Dock 类型归属校验（TC-WAYLINE-013/014/015）：
 * flighttask_stop 和 return_specific_home 仅 Dock2/3 支持，flight_setup_abort 仅 Dock1 支持。</p>
 */
@Component
public class WaylineTaskSimulator {

    private static final Logger log = LoggerFactory.getLogger(WaylineTaskSimulator.class);

    /** 任务进度推进间隔（秒） */
    private static final long PROGRESS_INTERVAL_SECONDS = 3;

    /** 返航飞行模拟延迟（秒）：return_home 后延迟更新位置到机场，使平台可观察 mode_code=9 → 0 过渡 */
    private static final long RETURN_HOME_DELAY_SECONDS = 5;

    /**
     * Dock1 任务执行步骤序列（current_step 枚举按 Dock1 wayline.html）。
     * <p>选择 6 个关键步骤（开机→起飞→返航检查→降落→退出工作模式→通知结果），
     * 不含"航线执行中(23)"以保证三版本 stepIndex 语义一致（Dock2 文档跳过了该步骤）。</p>
     * <p>序列：7=开机检查+开盖 → 22=触发执行航线(起飞) → 24=进入返航检查 → 25=飞行器降落机场 → 27=机场退出工作模式 → 33=通知任务结果</p>
     * <p>核实依据：[Dock1 wayline.html] flighttask_progress progress.current_step 枚举</p>
     */
    private static final int[] STEP_SEQUENCE_DOCK1 = {7, 22, 24, 25, 27, 33};
    /**
     * Dock2/Dock3 任务执行步骤序列（current_step 枚举按 Dock2/Dock3 wayline.html）。
     * <p>Dock2/3 比 Dock1 多 step 8(图传远程对频) 和 step 22(起飞机场检查降落机场准备状态)，
     * 且 Dock2 跳过了 step 25(航线执行中)，故 Dock2/3 的 step 值整体偏移+2。</p>
     * <p>序列：7=开机检查+开盖 → 24=触发执行航线(起飞) → 26=进入返航检查 → 27=飞行器降落机场 → 29=机场退出工作模式 → 35=通知任务结果</p>
     * <p>核实依据：[Dock2 wayline.html] / [Dock3 wayline.html] flighttask_progress progress.current_step 枚举</p>
     */
    private static final int[] STEP_SEQUENCE_DOCK2_3 = {7, 24, 26, 27, 29, 35};
    /** 每个步骤对应的 percent（与步骤序列一一对应，各 Dock 版本通用，6 步） */
    private static final int[] STEP_PERCENTS = {5, 20, 60, 80, 90, 100};

    /**
     * break_reason 三版本共有枚举值集合（Dock1/Dock2/Dock3 wayline.html 交集）。
     * <p>型号差异（仅 528/529 不同，其余三版本一致）：
     * <ul>
     *   <li>528=接近用户自定义飞行区边界：仅 Dock1</li>
     *   <li>529=有障碍物或者禁飞区域，导致航线无法到达：仅 Dock2</li>
     *   <li>1565=航线避障紧急刹停：三版本均有（在 BASE 中）</li>
     * </ul>
     * <p>核实依据：[Dock1/Dock2/Dock3 wayline.html] flighttask_progress ext.break_point.break_reason 枚举</p>
     */
    private static final Set<Integer> BREAK_REASON_BASE = Set.of(
            0, 1, 2, 4, 5, 6, 7,
            257, 258, 259, 261, 262,
            513, 514, 515, 516, 517, 518, 519, 521, 522, 523, 524, 526, 527,
            769, 770, 771, 772, 773, 775, 778, 779, 780, 781, 784,
            1281, 1282, 1283,
            1539, 1540, 1541, 1542, 1543, 1544, 1545, 1546, 1547, 1548, 1549, 1550, 1551, 1552, 1553,
            1555, 1556, 1557, 1558, 1559, 1560, 1561, 1563, 1564, 1565,
            1588, 1595, 1598, 1602, 1603, 1606, 1608, 1609, 1610, 1611, 1612, 1613, 1614, 1634, 1649,
            65534, 65535);
    /** 528=接近用户自定义飞行区边界（仅 Dock1） */
    private static final int BREAK_REASON_528 = 528;
    /** 529=有障碍物或者禁飞区域，导致航线无法到达（仅 Dock2） */
    private static final int BREAK_REASON_529 = 529;

    /**
     * 飞行器地面态 mode_code 集合（可取消任务）：
     * 0=待机, 1=起飞准备, 2=起飞准备完毕。
     * <p>核实依据：[M30 mode_code 枚举](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html)</p>
     */
    private static final Set<Integer> DRONE_GROUND_MODES = Set.of(0, 1, 2);

    /**
     * 飞行器异常态 mode_code 集合（拒绝取消任务，返回 326108）：
     * 13=升级中, 14=未连接。
     */
    private static final Set<Integer> DRONE_ABNORMAL_MODES = Set.of(13, 14);

    /** DJI 错误码：当前状态不支持（飞行器异常态取消任务）。核实依据：Dock1 wayline.html flight_setup_abort 返回码 */
    private static final int ERR_STATE_NOT_SUPPORTED = 326108;

    /** DJI 错误码：飞行器已经起飞，不支持取消（飞行中取消任务）。核实依据：Dock1 wayline.html flight_setup_abort 返回码 */
    private static final int ERR_DRONE_ALREADY_TAKEOFF = 326109;

    /** requests_reply 等待超时（秒） */
    private static final long REPLY_TIMEOUT_SECONDS = 10;

    /** tid → CompletableFuture，用于等待 requests_reply */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingReplies = new ConcurrentHashMap<>();

    /** 已注册 requests_reply 监听器的 dockSn（dockSn 变化时需重新注册） */
    private volatile String registeredReplyDockSn;

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final ObjectMapper objectMapper;
    private final ServiceCommandHandler commandHandler;
    private final MediaUploadSimulator mediaUploadSimulator;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final DockTopicSchema dockTopicSchema;

    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ScheduledFuture<?>> progressTask = new AtomicReference<>();

    /** 当前任务信息 */
    private volatile String currentFlightId;
    private volatile String currentTrackId;
    private volatile int currentStepIndex;
    private volatile boolean paused;

    public WaylineTaskSimulator(SimulatorProperties props, MqttClientManager mqtt,
                                DeviceState state, ObjectMapper objectMapper,
                                ServiceCommandHandler commandHandler,
                                MediaUploadSimulator mediaUploadSimulator,
                                RuntimeConfig runtimeConfig,
                                DiagnosticLogRecorder diagnosticRecorder,
                                DockTopicSchema dockTopicSchema) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.commandHandler = commandHandler;
        this.mediaUploadSimulator = mediaUploadSimulator;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
        this.dockTopicSchema = dockTopicSchema;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wayline-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void init() {
        // 向 ServiceCommandHandler 注册航线命令处理器
        commandHandler.setWaylineHandler(this::handleWaylineCommand);
        log.info("WaylineTaskSimulator 已注册航线命令处理器");
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    // ==================== 命令处理 ====================

    /**
     * 处理航线相关 services 命令，返回 output（含 result）。
     * <p>包含 Dock 类型归属校验（TC-WAYLINE-013/014/015）：
     * <ul>
     *   <li>flighttask_stop：仅 Dock2/3 支持（Dock1 用 flighttask_undo 替代）</li>
     *   <li>return_specific_home：仅 Dock2/3 支持（蛙跳场景）</li>
     *   <li>flight_setup_abort：仅 Dock1 支持（取消准备中的任务）</li>
     * </ul>
     */
    private Map<String, Object> handleWaylineCommand(String method, JsonNode data) {
        log.info("处理航线命令: method={}, flightId={}", method,
                data != null ? data.path("flight_id").asText() : "null");

        // Dock 类型归属校验
        if (!isCommandSupported(method)) {
            log.warn("[P-8] 当前 Dock 类型 {} 不支持命令: {}", runtimeConfig.getDockType().getShortName(), method);
            diagnosticRecorder.record(DiagnosticCode.PLATFORM_DOCK_CAPABILITY_MISMATCH, method,
                    "Dock " + runtimeConfig.getDockType().getShortName() + " 不支持此命令");
            return Map.of("result", 1);
        }

        return switch (method) {
            case "flighttask_prepare" -> handlePrepare(data);
            case "flighttask_execute" -> handleExecute(data);
            case "flighttask_pause" -> handlePause();
            case "flighttask_recovery" -> handleRecovery();
            case "flighttask_undo", "flighttask_stop" -> handleStop(data);
            case "return_home" -> handleReturnHome();
            case "return_home_cancel" -> handleReturnHomeCancel();
            case "return_specific_home" -> handleReturnSpecificHome(data);
            case "flight_setup_abort" -> handleFlightSetupAbort();
            case "in_flight_wayline_deliver" -> handleInFlightWaylineDeliver(data);
            case "in_flight_wayline_stop" -> handleInFlightWaylineStop(data);
            case "in_flight_wayline_recover" -> handleInFlightWaylineRecover(data);
            case "in_flight_wayline_cancel" -> handleInFlightWaylineCancel();
            default -> Map.of("result", 0);
        };
    }

    /**
     * 检查当前 Dock 类型是否支持指定命令。
     * <p>Dock 差异（基于 DJI wayline.html 核实）：
     * <ul>
     *   <li>flighttask_stop：仅 Dock2/3 支持（Dock1 用 flighttask_undo + flight_setup_abort 替代）</li>
     *   <li>return_specific_home：仅 Dock2/3 支持（指定 home 点返航，蛙跳场景）</li>
     *   <li>flight_setup_abort：仅 Dock1 支持（取消准备中的任务，Home 点设置阶段）</li>
     * </ul>
     * <p>核实依据：[Dock1 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) |
     * [Dock3 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html)
     */
    private boolean isCommandSupported(String method) {
        DeviceType dockType = runtimeConfig.getDockType();
        return switch (method) {
            case "flighttask_stop", "return_specific_home" -> dockType != DeviceType.DOCK1;
            case "flight_setup_abort" -> dockType == DeviceType.DOCK1;
            default -> true;
        };
    }

    /**
     * 按当前 Dock 型号返回任务执行步骤序列。
     * <p>Dock1 与 Dock2/Dock3 的 current_step 枚举存在偏移（Dock2/3 多"图传远程对频""起飞机场检查降落机场"两步），
     * 必须按型号选择，否则 step 值语义错误（如 Dock1 的 24=返航检查，在 Dock3 下 24=触发执行航线）。</p>
     */
    private int[] stepSequence() {
        return runtimeConfig.getDockType() == DeviceType.DOCK1 ? STEP_SEQUENCE_DOCK1 : STEP_SEQUENCE_DOCK2_3;
    }

    /**
     * 校验 break_reason 在当前 Dock 型号中是否合法。
     * <p>三版本共有值在 BASE 中（含 1565=航线避障紧急刹停）；型号专有值：
     * <ul>
     *   <li>528=接近用户自定义飞行区边界：仅 Dock1</li>
     *   <li>529=有障碍物或者禁飞区域，导致航线无法到达：仅 Dock2</li>
     * </ul>
     * <p>模拟器作为设备方只发送该型号合法的值，非法值拒绝发送（P-8 型号能力不匹配）。</p>
     * <p>核实依据：[Dock1/Dock2/Dock3 wayline.html] break_reason 枚举对比</p>
     */
    private boolean isBreakReasonValid(int breakReason) {
        DeviceType dockType = runtimeConfig.getDockType();
        // 三版本共有值
        if (BREAK_REASON_BASE.contains(breakReason)) {
            return true;
        }
        // 528=接近用户自定义飞行区边界（仅 Dock1）
        if (breakReason == BREAK_REASON_528) {
            return dockType == DeviceType.DOCK1;
        }
        // 529=有障碍物或者禁飞区域，导致航线无法到达（仅 Dock2）
        if (breakReason == BREAK_REASON_529) {
            return dockType == DeviceType.DOCK2;
        }
        return false;
    }

    /**
     * 按当前 Dock 型号返回默认 break_reason（用于障碍物/禁飞区场景测试）。
     * <p>dock1=528（接近用户自定义飞行区边界）；dock2=529（障碍物/禁飞区域航线无法到达，dock2 专有）；
     * dock3=517（飞行器触发避障，dock3 无自定义飞行区对应枚举值）。</p>
     */
    private int defaultBreakReason() {
        DeviceType dockType = runtimeConfig.getDockType();
        if (dockType == DeviceType.DOCK1) {
            return 528;
        }
        if (dockType == DeviceType.DOCK2) {
            return 529;
        }
        return 517; // DOCK3 及其他
    }

    /**
     * flighttask_prepare：任务准备。回复 result=0，更新 dock 状态。
     */
    private Map<String, Object> handlePrepare(JsonNode data) {
        if (data != null && data.has("flight_id")) {
            currentFlightId = data.path("flight_id").asText();
        }

        // 解析任务参数（当前仅解析记录，为未来功能扩展做准备）
        if (data != null) {
            logFlightTaskPrepareParams(data);
            // 提取返航高度到 state，供 return_home_info 使用
            // DJI 文档约束：rth_altitude int, min=20, max=1500, 单位 m（相对起飞点 ALT）
            if (data.has("rth_altitude")) {
                state.setRthAltitude(data.path("rth_altitude").asInt());
            }
        }

        // 机场进入工作模式
        state.setDockModeCode(1);
        state.setCoverOpen(true);
        state.setDroneChargeState(0);
        log.info("任务准备完成: flightId={}", currentFlightId);
        return Map.of("result", 0);
    }

    /**
     * 解析 flighttask_prepare 请求参数并记录日志。
     * <p>核实依据：[Dock3 wayline.html] Service flighttask_prepare Data。
     * 当前仅解析记录，为未来功能扩展（条件任务/断点续飞/模拟器任务等）做准备。</p>
     */
    private void logFlightTaskPrepareParams(JsonNode data) {
        // 任务类型
        int taskType = data.path("task_type").asInt(-1);
        String taskTypeStr = switch (taskType) {
            case 0 -> "立即任务";
            case 1 -> "定时任务";
            case 2 -> "条件任务";
            default -> "未知(" + taskType + ")";
        };
        log.info("任务参数: flightId={}, taskType={}, executeTime={}",
                data.path("flight_id").asText(),
                taskTypeStr,
                data.has("execute_time") ? data.path("execute_time").asLong() : "-");

        // 航线文件
        JsonNode file = data.path("file");
        if (!file.isMissingNode()) {
            log.info("航线文件: url={}, fingerprint={}", file.path("url").asText(), file.path("fingerprint").asText());
        }

        // 返航参数与失控动作
        log.info("返航/失控参数: rthAltitude={}, rthMode={}, outOfControlAction={}, exitWaylineWhenRcLost={}, waylinePrecisionType={}",
                data.has("rth_altitude") ? data.path("rth_altitude").asInt() : "-",
                data.has("rth_mode") ? data.path("rth_mode").asInt() : "-",
                data.has("out_of_control_action") ? data.path("out_of_control_action").asInt() : "-",
                data.has("exit_wayline_when_rc_lost") ? data.path("exit_wayline_when_rc_lost").asInt() : "-",
                data.has("wayline_precision_type") ? data.path("wayline_precision_type").asInt() : "-");

        // 条件任务就绪条件（task_type=2 时必填）
        JsonNode readyConditions = data.path("ready_conditions");
        if (!readyConditions.isMissingNode()) {
            log.info("任务就绪条件: batteryCapacity={}, beginTime={}, endTime={}",
                    readyConditions.path("battery_capacity").asInt(),
                    readyConditions.path("begin_time").asLong(),
                    readyConditions.path("end_time").asLong());
        }

        // 执行条件
        JsonNode executableConditions = data.path("executable_conditions");
        if (!executableConditions.isMissingNode()) {
            log.info("执行条件: storageCapacity={}", executableConditions.path("storage_capacity").asInt());
        }

        // 断点续飞
        JsonNode breakPoint = data.path("break_point");
        if (!breakPoint.isMissingNode()) {
            log.info("断点续飞: index={}, state={}, progress={}, waylineId={}",
                    breakPoint.path("index").asInt(),
                    breakPoint.path("state").asInt(),
                    breakPoint.path("progress").asDouble(),
                    breakPoint.path("wayline_id").asInt());
        }

        // 模拟器任务
        JsonNode simulateMission = data.path("simulate_mission");
        if (!simulateMission.isMissingNode()) {
            log.info("模拟器任务: isEnable={}, lat={}, lng={}, alt={}",
                    simulateMission.path("is_enable").asInt(),
                    simulateMission.path("latitude").asDouble(),
                    simulateMission.path("longitude").asDouble(),
                    simulateMission.path("altitude").asDouble());
        }

        // 飞行安全预检查
        if (data.has("flight_safety_advance_check")) {
            log.info("飞行安全预检查: {}", data.path("flight_safety_advance_check").asInt());
        }
    }

    /**
     * flighttask_execute：任务执行。回复 result=0，启动异步进度推进。
     * <p>解析 multi_dock_task 蛙跳任务参数（如有），当前仅解析记录，未用于执行逻辑。</p>
     */
    private Map<String, Object> handleExecute(JsonNode data) {
        if (data != null && data.has("flight_id")) {
            currentFlightId = data.path("flight_id").asText();
        }

        // 解析蛙跳任务参数（multi_dock_task），当前仅解析记录，为未来蛙跳任务支持做准备
        if (data != null && data.has("multi_dock_task")) {
            parseMultiDockTask(data.path("multi_dock_task"));
        }

        currentTrackId = UUID.randomUUID().toString();
        currentStepIndex = 0;
        paused = false;

        // 无人机起飞
        state.setDroneInDock(false);
        state.setDroneModeCode(4); // 自动起飞
        state.setPutterExpanded(true);

        // 启动进度推进任务
        startProgressTask();
        log.info("任务执行已启动: flightId={}, trackId={}", currentFlightId, currentTrackId);
        return Map.of("result", 0);
    }

    /**
     * 解析蛙跳任务参数（multi_dock_task）。
     * <p>核实依据：[Dock3 wayline.html] Service flighttask_execute Data.multi_dock_task。
     * 当前仅解析记录日志，未用于执行逻辑。未来实现蛙跳任务时可直接复用此解析逻辑。</p>
     */
    private void parseMultiDockTask(JsonNode multiDockTask) {
        // 图传连接拓扑
        JsonNode wirelessLinkTopo = multiDockTask.path("wireless_link_topo");
        if (!wirelessLinkTopo.isMissingNode()) {
            JsonNode secretCode = wirelessLinkTopo.path("secret_code");
            JsonNode centerNode = wirelessLinkTopo.path("center_node");
            JsonNode leafNodes = wirelessLinkTopo.path("leaf_nodes");
            log.info("蛙跳任务图传拓扑: secret_code.size={}, center_node.sn={}, leaf_nodes.size={}",
                    secretCode.size(), centerNode.path("sn").asText(), leafNodes.size());
        }

        // 机场信息
        JsonNode dockInfos = multiDockTask.path("dock_infos");
        if (dockInfos.isArray()) {
            for (JsonNode dockInfo : dockInfos) {
                log.info("蛙跳任务机场: sn={}, dock_type={}, index={}, lat={}, lng={}, height={}",
                        dockInfo.path("sn").asText(),
                        dockInfo.path("dock_type").asText(),
                        dockInfo.path("index").asInt(),
                        dockInfo.path("latitude").asDouble(),
                        dockInfo.path("longitude").asDouble(),
                        dockInfo.path("height").asDouble());
            }
        }

        // M-2：蛙跳任务参数已解析但当前不用于执行逻辑
        String inference = "flighttask_execute的multi_dock_task蛙跳任务参数已解析（wireless_link_topo/dock_infos），"
                + "但当前模拟器仅支持普通航线任务，未将蛙跳参数用于执行逻辑，待后续实现蛙跳任务支持";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "flighttask_execute", inference);
        log.warn("[M-2] 蛙跳任务参数已解析但未用于执行逻辑，待后续实现蛙跳任务支持");
    }

    /**
     * flighttask_pause：暂停任务。
     */
    private Map<String, Object> handlePause() {
        paused = true;
        state.setDroneModeCode(5); // 航线飞行→暂停（保持悬停）
        log.info("任务已暂停: flightId={}", currentFlightId);
        return Map.of("result", 0);
    }

    /**
     * flighttask_recovery：恢复任务。
     */
    private Map<String, Object> handleRecovery() {
        paused = false;
        state.setDroneModeCode(5); // 航线飞行
        log.info("任务已恢复: flightId={}", currentFlightId);
        return Map.of("result", 0);
    }

    /**
     * flighttask_stop / flighttask_undo：停止/取消任务。
     * <p>解析请求参数：
     * <ul>
     *   <li>flighttask_stop：flight_id + reason（0=正常结束, 1=另一机场状态机异常，蛙跳场景）</li>
     *   <li>flighttask_undo：flight_ids（取消的任务 ID 数组）</li>
     * </ul>
     * <p>根据飞行器 mode_code 区分处理（TC-WAYLINE-004/022/023）：
     * <ul>
     *   <li>地面态（{0, 1, 2}）：取消任务，上报 canceled，完整恢复 dock 状态</li>
     *   <li>飞行中（{3-12}）：返回 326109（飞行器已经起飞，不支持取消），不修改任何状态</li>
     *   <li>异常态（{13, 14}）：返回 326108（当前状态不支持），不修改任何状态</li>
     * </ul>
     * <p>核实依据：[Dock1 wayline.html 取消准备中的任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)
     * 返回码 326108/326109 原文</p>
     */
    private Map<String, Object> handleStop(JsonNode data) {
        // 解析请求参数（flighttask_stop 的 flight_id/reason，flighttask_undo 的 flight_ids）
        if (data != null) {
            if (data.has("flight_id")) {
                log.info("任务终止请求: flight_id={}, reason={}", data.path("flight_id").asText(),
                        data.has("reason") ? data.path("reason").asInt() : "-");
            }
            if (data.has("flight_ids")) {
                log.info("取消任务请求: flight_ids={}", data.path("flight_ids"));
            }
        }

        int modeCode = state.getDroneModeCode();

        // 异常态：返回 326108（当前状态不支持）
        if (DRONE_ABNORMAL_MODES.contains(modeCode)) {
            log.warn("取消任务失败，飞行器异常态 mode_code={}: flightId={}", modeCode, currentFlightId);
            return Map.of("result", ERR_STATE_NOT_SUPPORTED);
        }

        // 飞行中：返回 326109（飞行器已经起飞，不支持取消，可通过返航按钮取消）
        if (!DRONE_GROUND_MODES.contains(modeCode)) {
            log.warn("取消任务失败，飞行器已起飞 mode_code={}: flightId={}", modeCode, currentFlightId);
            return Map.of("result", ERR_DRONE_ALREADY_TAKEOFF);
        }

        // 地面态：取消任务 + 完整恢复 dock 状态
        stopProgressTask();
        if (currentFlightId != null) {
            publishProgress("canceled", currentStepIndex, STEP_PERCENTS[Math.min(currentStepIndex, STEP_PERCENTS.length - 1)]);
        }
        resetDroneToHomeState();
        resetTaskState();
        log.info("任务已停止: flightId={}", currentFlightId);
        return Map.of("result", 0);
    }

    /**
     * return_home：返航。
     * <p>立即设置 mode_code=9（自动返航），停止当前航线任务进度，
     * 并调度延迟任务模拟返航飞行后更新位置到机场（TC-LOC-016）。</p>
     * <p>延迟更新而非立即更新，使平台可观察到 mode_code=9（返航）→ mode_code=0（待机）的状态过渡。</p>
     */
    private Map<String, Object> handleReturnHome() {
        // 停止当前航线任务进度（若在执行中）
        stopProgressTask();

        // 设置返航模式
        state.setDroneModeCode(9); // 自动返航

        // 调度延迟任务：模拟返航飞行后更新位置到机场
        ScheduledFuture<?> task = scheduler.schedule(this::completeReturnHome,
                RETURN_HOME_DELAY_SECONDS, TimeUnit.SECONDS);
        progressTask.set(task);

        // M-2：return_home 命令的后续行为（不发 return_home_info、无进度上报）DJI 文档未明确，待真机验证
        String inference = "return_home命令后续行为：不发return_home_info（该事件含flight_id属航线任务关联）+ 无进度上报（flighttask_progress的返航阶段属航线任务）"
                + "，DJI文档未明确return_home命令的后续事件/进度机制，待真机验证";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "return_home", inference);
        log.warn("[M-2] return_home 后续行为未确认: 不发return_home_info + 无进度上报，待真机验证");

        log.info("无人机返航: flightId={}", currentFlightId);

        // Dock3 文档 reply 含 output.status；Pilot 文档 reply 仅 result（无 output）
        if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
            return Map.of("result", 0);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "ok");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", 0);
        result.put("output", output);
        return result;
    }

    /**
     * 返航完成：更新无人机位置到机场，恢复 dock 待机状态。
     * <p>由 {@link #handleReturnHome()} 延迟调度，模拟返航飞行时间。
     * 复用 {@link #resetDroneToHomeState()} 归舱逻辑，确保位置/模式/dock 状态一致。</p>
     * <p>对应 TC-LOC-016 return_home 后无人机位置更新到机场。</p>
     */
    private void completeReturnHome() {
        String flightId = currentFlightId;
        resetDroneToHomeState();
        resetTaskState();
        log.info("无人机返航完成，已回到机场位置: flightId={}", flightId);
    }

    /**
     * return_home_cancel：取消返航。
     * <p>取消返航延迟任务，无人机在当前位置悬停。</p>
     * <p>核实依据：[Dock3 wayline.html 取消返航](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html)</p>
     */
    private Map<String, Object> handleReturnHomeCancel() {
        stopProgressTask(); // 取消返航延迟任务
        log.info("取消返航: flightId={}", currentFlightId);
        return Map.of("result", 0);
    }

    /**
     * return_specific_home：指定 home 点返航（蛙跳任务）。
     * <p>解析 home_dock_sn，当前仅解析记录，为未来蛙跳任务支持做准备。
     * 核实依据：[Dock3 wayline.html] Service return_specific_home Data.home_dock_sn。</p>
     */
    private Map<String, Object> handleReturnSpecificHome(JsonNode data) {
        if (data != null && data.has("home_dock_sn")) {
            log.info("指定home点返航: homeDockSn={}", data.path("home_dock_sn").asText());
        }
        return Map.of("result", 0);
    }

    /**
     * in_flight_wayline_deliver：空中下发航线。
     * <p>飞行器处于空中飞行时，下发文件体积较小的航线。
     * 解析所有请求字段并记录日志，回复 result=0。
     * 核实依据：[Dock3 wayline.html] Service in_flight_wayline_deliver Data。</p>
     */
    private Map<String, Object> handleInFlightWaylineDeliver(JsonNode data) {
        if (data != null) {
            String waylineId = data.path("in_flight_wayline_id").asText();
            JsonNode file = data.path("file");
            log.info("空中下发航线: waylineId={}, file.url={}, file.fingerprint={}",
                    waylineId,
                    file.path("url").asText(),
                    file.path("fingerprint").asText());
            log.info("空中航线参数: outOfControlAction={}, exitWaylineWhenRcLost={}, rthAltitude={}, rthMode={}, waylinePrecisionType={}",
                    data.has("out_of_control_action") ? data.path("out_of_control_action").asInt() : "-",
                    data.has("exit_wayline_when_rc_lost") ? data.path("exit_wayline_when_rc_lost").asInt() : "-",
                    data.has("rth_altitude") ? data.path("rth_altitude").asInt() : "-",
                    data.has("rth_mode") ? data.path("rth_mode").asInt() : "-",
                    data.has("wayline_precision_type") ? data.path("wayline_precision_type").asInt() : "-");
        }
        return Map.of("result", 0);
    }

    /**
     * in_flight_wayline_stop：暂停空中航线。
     * <p>解析 in_flight_wayline_id，回复 result=0。
     * 核实依据：[Dock3 wayline.html] Service in_flight_wayline_stop Data。</p>
     */
    private Map<String, Object> handleInFlightWaylineStop(JsonNode data) {
        if (data != null) {
            log.info("暂停空中航线: waylineId={}", data.path("in_flight_wayline_id").asText());
        }
        return Map.of("result", 0);
    }

    /**
     * in_flight_wayline_recover：恢复空中航线。
     * <p>解析 in_flight_wayline_id，回复 result=0。
     * 核实依据：[Dock3 wayline.html] Service in_flight_wayline_recover Data。</p>
     */
    private Map<String, Object> handleInFlightWaylineRecover(JsonNode data) {
        if (data != null) {
            log.info("恢复空中航线: waylineId={}", data.path("in_flight_wayline_id").asText());
        }
        return Map.of("result", 0);
    }

    /**
     * in_flight_wayline_cancel：取消空中航线。
     * <p>请求 Data 为空对象，回复 result=0。
     * 核实依据：[Dock3 wayline.html] Service in_flight_wayline_cancel Data。</p>
     */
    private Map<String, Object> handleInFlightWaylineCancel() {
        log.info("取消空中航线");
        return Map.of("result", 0);
    }

    /**
     * flight_setup_abort：取消准备中的任务（仅 Dock1）。
     * <p>DJI 文档：在 Home 点设置状态（current_step=21）进行调用，即起飞命令下发后
     * RTK 数据未收敛时取消任务。与 flighttask_undo 的区别是：flighttask_undo 仅取消
     * 未开始执行的任务，flight_setup_abort 在准备阶段（已 execute 但未起飞）取消。</p>
     * <p>mode_code 约束与 handleStop 一致（TC-WAYLINE-022/023）：飞行器起飞后调用
     * 返回 326109，异常态返回 326108。</p>
     * <p>核实依据：[Dock1 wayline.html 取消准备中的任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)</p>
     */
    private Map<String, Object> handleFlightSetupAbort() {
        int modeCode = state.getDroneModeCode();

        // 异常态：返回 326108（当前状态不支持）
        if (DRONE_ABNORMAL_MODES.contains(modeCode)) {
            log.warn("取消准备中任务失败，飞行器异常态 mode_code={}: flightId={}", modeCode, currentFlightId);
            return Map.of("result", ERR_STATE_NOT_SUPPORTED);
        }

        // 飞行中：返回 326109（飞行器已经起飞，不支持取消，可通过返航按钮取消）
        if (!DRONE_GROUND_MODES.contains(modeCode)) {
            log.warn("取消准备中任务失败，飞行器已起飞 mode_code={}: flightId={}", modeCode, currentFlightId);
            return Map.of("result", ERR_DRONE_ALREADY_TAKEOFF);
        }

        // 地面态：取消准备中的任务 + 完整恢复 dock 状态
        stopProgressTask();
        resetDroneToHomeState();
        resetTaskState();
        log.info("准备中的任务已取消（flight_setup_abort）");
        return Map.of("result", 0);
    }

    // ==================== 进度推进 ====================

    private void startProgressTask() {
        stopProgressTask();
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(this::advanceProgress,
                PROGRESS_INTERVAL_SECONDS, PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS);
        progressTask.set(task);
    }

    private void stopProgressTask() {
        ScheduledFuture<?> task = progressTask.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 推进任务进度：每次推进一个 current_step，到达 35 时完成任务。
     */
    private void advanceProgress() {
        if (paused || currentFlightId == null) {
            return;
        }
        try {
            int[] seq = stepSequence();
            if (currentStepIndex >= seq.length) {
                // 任务完成
                completeTask();
                return;
            }

            int step = seq[currentStepIndex];
            int percent = STEP_PERCENTS[currentStepIndex];
            String status = currentStepIndex < seq.length - 1 ? "in_progress" : "ok";

            // 根据步骤索引更新无人机状态（三版本 stepIndex 语义一致）
            updateDroneStateByStepIndex(currentStepIndex);

            publishProgress(status, currentStepIndex, percent);
            log.info("任务进度: flightId={}, step={}, percent={}, status={}",
                    currentFlightId, step, percent, status);

            currentStepIndex++;

            if ("ok".equals(status)) {
                completeTask();
            }
        } catch (Exception e) {
            log.error("推进任务进度异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 任务完成：停止推进，发 return_home_info，重置状态。
     */
    private void completeTask() {
        stopProgressTask();

        // 无人机降落归舱，恢复 dock 待机状态（复用归舱逻辑，避免重复代码）
        resetDroneToHomeState();

        // 发 return_home_info 事件
        publishReturnHomeInfo();

        String finishedFlightId = currentFlightId;
        resetTaskState();
        log.info("任务完成: flightId={}", finishedFlightId);

        // 触发媒体上传（使用 ForkJoinPool 异步执行，不阻塞 wayline-scheduler 线程池）
        if (finishedFlightId != null) {
            CompletableFuture.runAsync(() -> mediaUploadSimulator.simulateMediaUpload(finishedFlightId, 3));
        }
    }

    /**
     * 根据执行步骤索引更新无人机状态。
     * <p>三版本 STEP_SEQUENCE 的 stepIndex 语义一致（均选择 6 个关键步骤），
     * 因此按 stepIndex 更新状态，避免 Dock1/Dock2/3 的 current_step 值差异导致 case 不匹配。</p>
     * <p>位置更新策略（简化版，对齐用户期望"仅经纬度变化"）：
     * <ul>
     *   <li>stepIndex 0（开机检查+开盖）：dockModeCode=1</li>
     *   <li>stepIndex 1（起飞）：droneModeCode=4，位置=机场，高度=0</li>
     *   <li>stepIndex 2（返航检查）：droneModeCode=5，位置偏移约100米，高度=50</li>
     *   <li>stepIndex 3（降落）：droneModeCode=9，位置=机场，高度=20</li>
     *   <li>stepIndex 4（退出工作模式）：droneModeCode=10，位置=机场，高度=0</li>
     *   <li>stepIndex 5（通知任务结果）：droneModeCode=0</li>
     * </ul>
     * <p>高度字段 droneHeight 为"相对起飞点"高度，与用户期望一致。</p>
     */
    private void updateDroneStateByStepIndex(int stepIndex) {
        double baseLat = runtimeConfig.getLocationLatitude();
        double baseLng = runtimeConfig.getLocationLongitude();
        switch (stepIndex) {
            case 0 -> { state.setDockModeCode(1); } // 开机检查+开盖
            case 1 -> { // 起飞
                state.setDroneModeCode(4);
                state.setDroneLatitude(baseLat);
                state.setDroneLongitude(baseLng);
                state.setDroneHeight(0.0);
            }
            case 2 -> { // 返航检查：相对机场偏移约 100 米
                state.setDroneModeCode(5);
                state.setDroneLatitude(baseLat + 0.001);
                state.setDroneLongitude(baseLng + 0.001);
                state.setDroneHeight(50.0);
            }
            case 3 -> { // 降落：位置回到机场
                state.setDroneModeCode(9);
                state.setDroneLatitude(baseLat);
                state.setDroneLongitude(baseLng);
                state.setDroneHeight(20.0);
            }
            case 4 -> { // 退出工作模式
                state.setDroneModeCode(10);
                state.setDroneLatitude(baseLat);
                state.setDroneLongitude(baseLng);
                state.setDroneHeight(0.0);
            }
            case 5 -> { state.setDroneModeCode(0); } // 通知任务结果
        }
    }

    /**
     * 重置无人机到机场位置并恢复 dock 待机状态。
     * <p>用于任务完成（completeTask）和地面态取消任务（handleStop/handleFlightSetupAbort）的归舱逻辑。
     * 包含：无人机位置归位、droneModeCode=0、droneInDock=true、充电中、关盖/收推杆、dockModeCode=0。</p>
     * <p>对应 TC-WAYLINE-004 地面态取消任务的完整状态恢复预期。</p>
     */
    private void resetDroneToHomeState() {
        state.setDroneModeCode(0); // 待机
        state.setDroneInDock(true);
        state.setDroneChargeState(1); // 充电中
        state.setCoverOpen(false);
        state.setPutterExpanded(false);
        state.setDockModeCode(0); // 待机
        // 无人机归舱后位置重置为机场位置（避免前端显示残留的飞行中偏移位置）
        state.setDroneLatitude(runtimeConfig.getLocationLatitude());
        state.setDroneLongitude(runtimeConfig.getLocationLongitude());
        state.setDroneHeight(0.0);
    }

    /**
     * 重置任务状态。
     */
    private void resetTaskState() {
        currentFlightId = null;
        currentTrackId = null;
        currentStepIndex = 0;
        paused = false;
    }

    // ==================== 事件上报 ====================

    /**
     * 发布 flighttask_progress 事件。
     * <p>格式：{@code {bid, data:{output:{ext, progress:{current_step, percent}, status}, result:0}, tid, timestamp, method}}</p>
     * <p>当 status 为 paused/failed/canceled 时，ext 中添加 break_point 断点信息。</p>
     */
    public void publishProgress(String status, int stepIndex, int percent) {
        int breakReason = switch (status) {
            case "paused" -> 1282;
            case "failed" -> 65535;
            case "canceled" -> 1281;
            default -> 0;
        };
        publishProgress(status, stepIndex, percent, breakReason);
    }

    /**
     * 发送 flighttask_progress 事件，支持自定义 break_reason。
     * <p>当 break_reason ≠ 0 时自动添加 break_point 断点信息。</p>
     * <p>break_reason 按当前 Dock 型号校验：非法值拒绝发送并记录 P-8 诊断。
     * 529=障碍物/禁飞区域航线无法到达（dock2 专有，dock1/dock3 无此值）。</p>
     *
     * @param status       任务状态（paused/failed/canceled/in_progress/ok）
     * @param stepIndex    当前步骤索引
     * @param percent      进度百分比
     * @param breakReason  中断原因码（0=无异常；按型号合法值见 isBreakReasonValid）
     */
    public void publishProgress(String status, int stepIndex, int percent, int breakReason) {
        // 校验 break_reason 是否在当前 Dock 型号合法
        if (breakReason != 0 && !isBreakReasonValid(breakReason)) {
            log.warn("[P-8] break_reason={} 在当前 Dock 类型 {} 中非法，拒绝发送 flighttask_progress",
                    breakReason, runtimeConfig.getDockType().getShortName());
            diagnosticRecorder.record(DiagnosticCode.PLATFORM_DOCK_CAPABILITY_MISMATCH, "flighttask_progress",
                    "break_reason=" + breakReason + " 在 Dock " + runtimeConfig.getDockType().getShortName() + " 中非法");
            return;
        }

        int[] seq = stepSequence();
        int currentStep = seq[Math.min(stepIndex, seq.length - 1)];

        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("current_waypoint_index", stepIndex * 2);
        ext.put("flight_id", currentFlightId != null ? currentFlightId : "");
        ext.put("media_count", stepIndex);
        ext.put("track_id", currentTrackId != null ? currentTrackId : "");
        ext.put("wayline_id", 0);
        ext.put("wayline_mission_state", "in_progress".equals(status) ? 6 : 9);

        // 任务中断/暂停/取消时，或有自定义 break_reason 时，添加 break_point 断点信息
        if (breakReason != 0 || "paused".equals(status) || "failed".equals(status) || "canceled".equals(status)) {
            int actualBreakReason = breakReason != 0 ? breakReason
                    : ("paused".equals(status) ? 1282 : ("failed".equals(status) ? 65535 : 1281));
            Map<String, Object> breakPoint = new LinkedHashMap<>();
            breakPoint.put("index", stepIndex);
            breakPoint.put("state", 0); // 在航段上
            breakPoint.put("progress", percent / 100.0);
            breakPoint.put("wayline_id", 0);
            breakPoint.put("break_reason", actualBreakReason);
            breakPoint.put("latitude", state.getDroneLatitude());
            breakPoint.put("longitude", state.getDroneLongitude());
            breakPoint.put("height", state.getDroneElevation());
            breakPoint.put("attitude_head", 0.0);
            ext.put("break_point", breakPoint);
        }

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("current_step", currentStep);
        progress.put("percent", percent);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ext", ext);
        output.put("progress", progress);
        output.put("status", status);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("output", output);
        data.put("result", 0);

        publishEvent("flighttask_progress", data);
    }

    /**
     * 发送 failed 状态的 flighttask_progress，使用指定的 break_reason。
     * <p>使用当前任务步骤和进度。break_reason 按当前 Dock 型号校验，非法值拒绝发送。</p>
     * <p>529=障碍物/禁飞区域航线无法到达（dock2 专有）；773=低电量返航；784=大风返航（三版本通用）。</p>
     *
     * @param breakReason 中断原因码（按型号合法值见 isBreakReasonValid）
     * @return true=已发送；false=break_reason 在当前 Dock 型号非法，未发送
     */
    public boolean publishProgressFailedWithBreakReason(int breakReason) {
        if (!isBreakReasonValid(breakReason)) {
            log.warn("[P-8] break_reason={} 在当前 Dock 类型 {} 中非法，拒绝发送 flighttask_progress(failed)",
                    breakReason, runtimeConfig.getDockType().getShortName());
            diagnosticRecorder.record(DiagnosticCode.PLATFORM_DOCK_CAPABILITY_MISMATCH, "flighttask_progress",
                    "break_reason=" + breakReason + " 在 Dock " + runtimeConfig.getDockType().getShortName() + " 中非法");
            return false;
        }
        int stepIndex = currentStepIndex;
        int percent = STEP_PERCENTS[Math.min(stepIndex, STEP_PERCENTS.length - 1)];
        publishProgress("failed", stepIndex, percent, breakReason);
        return true;
    }

    /**
     * 发送 failed 状态的 flighttask_progress，使用当前 Dock 型号的默认 break_reason。
     * <p>默认值：dock1=528（自定义飞行区边界）；dock2=529（障碍物/禁飞，dock2 专有）；dock3=517（触发避障）。</p>
     *
     * @return true=已发送；false=未发送（理论上默认值合法，不会返回 false）
     */
    public boolean publishProgressFailedWithBreakReason() {
        return publishProgressFailedWithBreakReason(defaultBreakReason());
    }

    /**
     * 发布 return_home_info 事件。
     * <p>返航轨迹使用 rth_altitude 构建：当前位置 → 升到返航高度 → 机场位置。
     * rth_altitude 为相对起飞点高度（ALT），需叠加机场椭球高转换为椭球高。</p>
     */
    private void publishReturnHomeInfo() {
        double droneLat = state.getDroneLatitude();
        double droneLng = state.getDroneLongitude();
        double droneHeight = state.getDroneElevation();
        double rthAltitude = state.getRthAltitude();
        double homeLat = runtimeConfig.getLocationLatitude();
        double homeLng = runtimeConfig.getLocationLongitude();
        double homeHeight = runtimeConfig.getLocationHeight();

        List<Map<String, Object>> pathPoints = new ArrayList<>();

        // 起点：无人机当前位置
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("latitude", droneLat);
        start.put("longitude", droneLng);
        start.put("height", droneHeight);
        pathPoints.add(start);

        // 中间点：当前高度低于返航高度时，先升到返航高度再水平飞回
        double rthHeight = homeHeight + rthAltitude;
        if (rthAltitude > 0 && droneHeight < rthHeight) {
            Map<String, Object> climb = new LinkedHashMap<>();
            climb.put("latitude", droneLat);
            climb.put("longitude", droneLng);
            climb.put("height", rthHeight);
            pathPoints.add(climb);
        }

        // 终点：机场位置
        Map<String, Object> home = new LinkedHashMap<>();
        home.put("latitude", homeLat);
        home.put("longitude", homeLng);
        home.put("height", homeHeight);
        pathPoints.add(home);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planned_path_points", pathPoints);
        data.put("last_point_type", 0);
        data.put("flight_id", currentFlightId != null ? currentFlightId : "");

        // home_dock_sn 和 multi_dock_home_info 仅 Dock2/3 支持（蛙跳场景），Dock1 无此字段
        DeviceType dockType = runtimeConfig.getDockType();
        if (dockType != DeviceType.DOCK1) {
            data.put("home_dock_sn", runtimeConfig.getDockSn());

            // 蛙跳任务机场返航信息（当前机场作为 home 点）
            List<Map<String, Object>> multiDockHomeInfo = new ArrayList<>();
            Map<String, Object> homeInfo = new LinkedHashMap<>();
            homeInfo.put("sn", runtimeConfig.getDockSn());
            homeInfo.put("plan_status", 3); // 目标可达
            homeInfo.put("estimated_battery_consumption", 30);
            homeInfo.put("home_distance", 0.0); // 当前机场就是 home 点
            multiDockHomeInfo.add(homeInfo);
            data.put("multi_dock_home_info", multiDockHomeInfo);
        }

        publishEvent("return_home_info", data);
    }

    /**
     * 发布 flighttask_ready 事件（任务就绪通知，无 need_reply）。
     * <p>核实依据：[Dock3 wayline.html] Event flighttask_ready，Data 含 flight_ids。</p>
     * @param flightIds 当前满足任务就绪条件的任务 ID 集合
     */
    public void publishFlighttaskReady(List<String> flightIds) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flight_ids", flightIds);
        publishEvent("flighttask_ready", data);
        log.info("已发送任务就绪通知: flightIds={}", flightIds);
    }

    /**
     * 发布 device_exit_homing_notify 事件（设备返航退出状态通知，need_reply=1）。
     * <p>核实依据：[Dock1/Dock2/Dock3 wayline.html] Event device_exit_homing_notify（三 Dock 通用）。</p>
     * @param action 0=退出返航退出状态, 1=进入返航退出状态
     * @param reason 退出返航原因 (0-10)
     */
    public void publishDeviceExitHomingNotify(int action, int reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", runtimeConfig.getDockSn());
        data.put("action", action);
        data.put("reason", reason);
        publishEventWithReply("device_exit_homing_notify", data);

        // M-2：reason 字段类型 DJI 文档字段定义为 enum_int，但示例中为字符串 "0"，待真机验证
        String inference = "device_exit_homing_notify的reason字段类型：DJI文档字段定义为enum_int，但示例中\"reason\":\"0\"为字符串。"
                + "当前选择int类型（按字段定义enum_int），待真机验证平台是否接受int类型";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "device_exit_homing_notify", inference);
        log.warn("[M-2] device_exit_homing_notify reason字段类型未确认: 使用int(按enum_int定义)，示例为字符串，待真机验证");

        log.info("已发送返航退出状态通知: action={}, reason={}", action, reason);
    }

    /**
     * 发布 flight_setup_exception_notify 事件（机场任务准备异常通知，need_reply=1）。
     * <p>该接口在航线任务和指令飞行任务准备阶段都支持上报。</p>
     * <p>归属：仅 Dock1（Dock2/Dock3 wayline 文档无此 event）。</p>
     * <p>核实依据：[Dock1 wayline.html] Event flight_setup_exception_notify。</p>
     * <p>字段（按 Example 顺序）：flight_id / flight_type / sn / timeout_time / timestamp</p>
     *
     * @param flightId 任务 ID（为空时 fallback 当前任务 currentFlightId，再为空则空串）
     * @param timeoutTime 异常超时时间（分钟，合法值 2/4/6/8/10）
     * @param flightType 任务类型（1=航线任务，2=指令飞行任务）
     * @return true=已发送；false=当前 Dock 类型不支持（仅 Dock1）未发送
     */
    public boolean publishFlightSetupExceptionNotify(String flightId, int timeoutTime, int flightType) {
        // P-8：仅 Dock1 支持该 event
        if (runtimeConfig.getDockType() != DeviceType.DOCK1) {
            log.warn("[P-8] 当前 Dock 类型 {} 不支持事件: flight_setup_exception_notify（仅 Dock1 支持）",
                    runtimeConfig.getDockType().getShortName());
            diagnosticRecorder.record(DiagnosticCode.PLATFORM_DOCK_CAPABILITY_MISMATCH, "flight_setup_exception_notify",
                    "Dock " + runtimeConfig.getDockType().getShortName() + " 不支持此事件（仅 Dock1）");
            return false;
        }

        String effectiveFlightId = (flightId != null && !flightId.isEmpty())
                ? flightId
                : (currentFlightId != null ? currentFlightId : "");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flight_id", effectiveFlightId);
        data.put("flight_type", flightType);
        data.put("sn", runtimeConfig.getDockSn());
        data.put("timeout_time", timeoutTime);
        data.put("timestamp", (double) System.currentTimeMillis());
        publishEventWithReply("flight_setup_exception_notify", data);

        // M-2：flight_id 字段 DJI 文档 Data 表未列出，但 Example 中包含，按 Example 实现，待真机验证
        String inference = "flight_setup_exception_notify的flight_id字段：DJI文档Data表仅定义sn/timeout_time/timestamp/flight_type四个字段，"
                + "但Example中包含flight_id。当前按Example实现(包含flight_id)，待真机验证平台是否需要该字段关联任务";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "flight_setup_exception_notify", inference);
        log.warn("[M-2] flight_setup_exception_notify flight_id字段未确认: Data表未列出但Example包含，按Example实现(包含)，待真机验证");

        log.info("已发送任务准备异常通知: flightId={}, timeout_time={}min, flight_type={}", effectiveFlightId, timeoutTime, flightType);
        return true;
    }

    /**
     * 发布 in_flight_wayline_progress 事件（空中下发航线状态上报，无 need_reply）。
     * <p>核实依据：[Dock3 wayline.html] Event in_flight_wayline_progress。</p>
     * @param waylineId 航线任务 ID
     * @param percent 完成百分比 (0-100)
     * @param status 任务状态码 (1-8)
     * @param result 错误原因码
     * @param wayPointIndex 当前航点索引
     */
    public void publishInFlightWaylineProgress(String waylineId, int percent, int status, int result, int wayPointIndex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("in_flight_wayline_id", waylineId);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("percent", percent);
        data.put("progress", progress);
        data.put("status", status);
        data.put("result", result);
        data.put("way_point_index", wayPointIndex);
        publishEvent("in_flight_wayline_progress", data);
        log.info("已发送空中下发航线状态: waylineId={}, percent={}, status={}", waylineId, percent, status);
    }

    /**
     * 发布事件到 thing/product/{sn}/events。
     */
    private void publishEvent(String method, Map<String, Object> data) {
        publishEventCore(method, data, 0);
    }

    /**
     * 发布事件到 thing/product/{sn}/events，need_reply=1。
     */
    private void publishEventWithReply(String method, Map<String, Object> data) {
        publishEventCore(method, data, 1);
    }

    private void publishEventCore(String method, Map<String, Object> data, int needReply) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("need_reply", needReply);
        envelope.put("gateway", runtimeConfig.getDockSn());
        envelope.put("method", method);
        envelope.put("data", data);

        String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已发送事件: method={}, need_reply={}", method, needReply);
    }

    // ==================== Requests 方向（设备→平台） ====================

    /**
     * 发送 flighttask_progress_get 请求（蛙跳任务中查询另一机场的任务状态），等待平台回复。
     * <p>核实依据：[Dock2/Dock3 wayline.html] Requests flighttask_progress_get。
     * 回复结构：result + output{flight_id, progress{current_step, percent}, status}</p>
     * <p>请求字段按 Dock 类型区分：
     * <ul>
     *   <li>Dock2: target_sn + flight_id</li>
     *   <li>Dock3: sn</li>
     * </ul></p>
     *
     * @return 平台回复的 JsonNode，超时返回 null
     */
    public JsonNode publishFlighttaskProgressGet(String targetSn, String flightId) {
        Map<String, Object> data = new LinkedHashMap<>();
        DeviceType dockType = runtimeConfig.getDockType();
        if (dockType == DeviceType.DOCK2) {
            // Dock2: target_sn（目标设备sn）+ flight_id（目标航线任务uuid）
            data.put("target_sn", targetSn);
            data.put("flight_id", flightId != null ? flightId : "");
        } else {
            // Dock3: sn（目标设备sn）
            data.put("sn", targetSn);
        }
        return sendRequestAndWaitReply("flighttask_progress_get", data);
    }

    /**
     * 发送 flighttask_resource_get 请求（获取任务航线文件资源），等待平台回复。
     * <p>核实依据：[Dock3 wayline.html] Requests flighttask_resource_get。
     * 回复结构：result + output{file{url, fingerprint}}</p>
     *
     * @return 平台回复的 JsonNode，超时返回 null
     */
    public JsonNode publishFlighttaskResourceGet(String flightId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flight_id", flightId);
        return sendRequestAndWaitReply("flighttask_resource_get", data);
    }

    /**
     * 发送 requests 方向消息并等待 requests_reply。
     * <p>使用 pendingReplies 机制匹配 tid，超时返回 null。</p>
     */
    private JsonNode sendRequestAndWaitReply(String method, Map<String, Object> data) {
        ensureReplyListener();

        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingReplies.put(tid, future);

        // 发送 requests 消息
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", bid);
        envelope.put("tid", tid);
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("gateway", runtimeConfig.getDockSn());
        envelope.put("method", method);
        envelope.put("data", data);
        String topic = dockTopicSchema.topic(dockTopicSchema.requests(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已发送 requests: method={}, tid={}", method, tid);

        try {
            JsonNode reply = future.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("收到 requests_reply: method={}, result={}", method,
                    reply.path("data").path("result").asText());
            return reply;
        } catch (Exception e) {
            pendingReplies.remove(tid);
            log.warn("等待 requests_reply 超时: method={}, {}", method, e.getMessage());
            return null;
        }
    }

    /**
     * 确保 requests_reply 监听器已注册（dockSn 变化时重新注册）。
     */
    private void ensureReplyListener() {
        String dockSn = runtimeConfig.getDockSn();
        if (dockSn.equals(registeredReplyDockSn)) {
            return;
        }
        String topic = dockTopicSchema.topic(dockTopicSchema.requestsReply(), dockSn);
        mqtt.addListener(topic, this::handleReply);
        registeredReplyDockSn = dockSn;
        log.info("已注册 requests_reply 监听器: dockSn={}", dockSn);
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

    // ==================== 状态查询（供 Web 控制台使用） ====================

    /**
     * 获取当前任务状态快照。
     * <p>无任务时仅返回 active=false，不返回误导性的 step/percent。</p>
     */
    public Map<String, Object> getTaskStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean active = currentFlightId != null;
        status.put("active", active);
        status.put("flight_id", currentFlightId);
        status.put("track_id", currentTrackId);
        status.put("paused", paused);
        if (active) {
            int[] seq = stepSequence();
            status.put("step_index", currentStepIndex);
            status.put("total_steps", seq.length);
            if (currentStepIndex < seq.length) {
                status.put("current_step", seq[currentStepIndex]);
                status.put("percent", STEP_PERCENTS[currentStepIndex]);
            }
        }
        return status;
    }
}
