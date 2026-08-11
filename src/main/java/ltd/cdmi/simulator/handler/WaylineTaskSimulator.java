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

package ltd.cdmi.simulator.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import ltd.cdmi.simulator.config.RuntimeConfig;
import ltd.cdmi.simulator.config.SimulatorProperties;
import ltd.cdmi.simulator.device.DeviceState;
import ltd.cdmi.simulator.device.DeviceType;
import ltd.cdmi.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.simulator.mqtt.MqttClientManager;
import ltd.cdmi.simulator.mqtt.TopicConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    /** 任务执行步骤序列（current_step: 7→24→25→27→28→35） */
    private static final int[] STEP_SEQUENCE = {7, 24, 25, 27, 28, 35};
    /** 每个步骤对应的 percent（与 STEP_SEQUENCE 一一对应） */
    private static final int[] STEP_PERCENTS = {5, 20, 60, 80, 90, 100};

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final ObjectMapper objectMapper;
    private final ServiceCommandHandler commandHandler;
    private final MediaUploadSimulator mediaUploadSimulator;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;

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
                                DiagnosticLogRecorder diagnosticRecorder) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.commandHandler = commandHandler;
        this.mediaUploadSimulator = mediaUploadSimulator;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
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
            case "flighttask_undo", "flighttask_stop" -> handleStop();
            case "return_home" -> handleReturnHome();
            case "return_home_cancel" -> Map.of("result", 0);
            case "return_specific_home" -> Map.of("result", 0);
            case "flight_setup_abort" -> handleFlightSetupAbort();
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
     * flighttask_prepare：任务准备。回复 result=0，更新 dock 状态。
     */
    private Map<String, Object> handlePrepare(JsonNode data) {
        if (data != null && data.has("flight_id")) {
            currentFlightId = data.path("flight_id").asText();
        }
        // 机场进入工作模式
        state.setDockModeCode(1);
        state.setCoverOpen(true);
        state.setDroneChargeState(0);
        log.info("任务准备完成: flightId={}", currentFlightId);
        return Map.of("result", 0);
    }

    /**
     * flighttask_execute：任务执行。回复 result=0，启动异步进度推进。
     */
    private Map<String, Object> handleExecute(JsonNode data) {
        if (data != null && data.has("flight_id")) {
            currentFlightId = data.path("flight_id").asText();
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
     */
    private Map<String, Object> handleStop() {
        stopProgressTask();
        // 上报任务取消
        if (currentFlightId != null) {
            publishProgress("canceled", currentStepIndex, STEP_PERCENTS[Math.min(currentStepIndex, STEP_PERCENTS.length - 1)]);
        }
        resetTaskState();
        log.info("任务已停止: flightId={}", currentFlightId);
        return Map.of("result", 0);
    }

    /**
     * return_home：返航。
     */
    private Map<String, Object> handleReturnHome() {
        state.setDroneModeCode(9); // 自动返航
        log.info("无人机返航: flightId={}", currentFlightId);
        return Map.of("result", 0);
    }

    /**
     * flight_setup_abort：取消准备中的任务（仅 Dock1）。
     * <p>DJI 文档：在 Home 点设置状态（current_step=21）进行调用，即起飞命令下发后
     * RTK 数据未收敛时取消任务。与 flighttask_undo 的区别是：flighttask_undo 仅取消
     * 未开始执行的任务，flight_setup_abort 在准备阶段（已 execute 但未起飞）取消。</p>
     * <p>核实依据：[Dock1 wayline.html 取消准备中的任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)</p>
     */
    private Map<String, Object> handleFlightSetupAbort() {
        stopProgressTask();
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
            if (currentStepIndex >= STEP_SEQUENCE.length) {
                // 任务完成
                completeTask();
                return;
            }

            int step = STEP_SEQUENCE[currentStepIndex];
            int percent = STEP_PERCENTS[currentStepIndex];
            String status = currentStepIndex < STEP_SEQUENCE.length - 1 ? "in_progress" : "ok";

            // 根据步骤更新无人机状态
            updateDroneStateByStep(step);

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

        // 无人机降落归舱，任务结束回到待机（mode=0）
        state.setDroneModeCode(0); // 待机
        state.setDroneInDock(true);
        state.setDroneChargeState(1); // 充电中
        state.setCoverOpen(false);
        state.setPutterExpanded(false);
        state.setDockModeCode(0); // 待机

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
     * 根据执行步骤更新无人机状态。
     */
    private void updateDroneStateByStep(int step) {
        switch (step) {
            case 7 -> { state.setDockModeCode(1); } // 开机检查+开盖
            case 24 -> { state.setDroneModeCode(4); } // 触发执行航线（起飞）
            case 25 -> { state.setDroneModeCode(5); state.setDroneHeight(50.0); } // 航线执行中
            case 27 -> { state.setDroneModeCode(9); state.setDroneHeight(20.0); } // 降落机场
            case 28 -> { state.setDroneModeCode(10); state.setDroneHeight(0.0); } // 关盖
            case 35 -> { state.setDroneModeCode(0); } // 通知任务结果
        }
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
     */
    public void publishProgress(String status, int stepIndex, int percent) {
        int currentStep = STEP_SEQUENCE[Math.min(stepIndex, STEP_SEQUENCE.length - 1)];

        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("current_waypoint_index", stepIndex * 2);
        ext.put("flight_id", currentFlightId != null ? currentFlightId : "");
        ext.put("media_count", stepIndex);
        ext.put("track_id", currentTrackId != null ? currentTrackId : "");
        ext.put("wayline_id", 0);
        ext.put("wayline_mission_state", "in_progress".equals(status) ? 6 : 9);

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("current_step", currentStep);
        progress.put("percent", percent);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ext", ext);
        output.put("flight_id", currentFlightId != null ? currentFlightId : "");
        output.put("progress", progress);
        output.put("status", status);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("output", output);
        data.put("result", 0);

        publishEvent("flighttask_progress", data);
    }

    /**
     * 发布 return_home_info 事件。
     */
    private void publishReturnHomeInfo() {
        Map<String, Object> pathPoint = new LinkedHashMap<>();
        pathPoint.put("latitude", props.location().latitude());
        pathPoint.put("longitude", props.location().longitude());
        pathPoint.put("height", props.location().height());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planned_path_points", List.of(pathPoint));
        data.put("last_point_type", 0);
        data.put("flight_id", currentFlightId != null ? currentFlightId : "");

        publishEvent("return_home_info", data);
    }

    /**
     * 发布事件到 thing/product/{sn}/events。
     */
    private void publishEvent(String method, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("data", data);
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("method", method);

        String topic = TopicConstants.topic(TopicConstants.EVENTS, props.device().dockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已发送事件: method={}", method);
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
            status.put("step_index", currentStepIndex);
            status.put("total_steps", STEP_SEQUENCE.length);
            if (currentStepIndex < STEP_SEQUENCE.length) {
                status.put("current_step", STEP_SEQUENCE[currentStepIndex]);
                status.put("percent", STEP_PERCENTS[currentStepIndex]);
            }
        }
        return status;
    }
}
