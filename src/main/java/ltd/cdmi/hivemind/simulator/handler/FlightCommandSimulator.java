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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 指令飞行模拟器（drc.html）。
 * <p>处理 DJI Cloud API「指令飞行」协议（走 services/events topic），与
 * {@link DrcCommandHandler}（远程控制 remote-control.html，走 drc/down/drc/up）是两套独立协议。
 * <p>职责：
 * <ul>
 *   <li>Service 指令：fly_to_point / takeoff_to_point（异步双阶段确认）、fly_to_point_stop / fly_to_point_update / flight_authority_grab / payload_authority_grab（同步）</li>
 *   <li>进度事件：fly_to_point_progress / takeoff_to_point_progress（bid 与原始 services 一致）</li>
 *   <li>设备主动上报事件：obstacle_avoidance_notify（仅 Dock3）、joystick_invalid_notify、camera_photo_take_progress、poi_status_notify（Dock1/Pilot）</li>
 * </ul>
 * <p>详见 DJI Cloud API <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html">指令飞行</a>。
 */
@Component
public class FlightCommandSimulator {

    private static final Logger log = LoggerFactory.getLogger(FlightCommandSimulator.class);

    /** 进度事件间隔（秒） */
    private static final long PROGRESS_INTERVAL_SECONDS = 2;

    /** 失联后降落/返航完成模拟延迟（秒） */
    private static final long RC_LOST_DELAY_SECONDS = 5;

    /** 无人机 mode_code：待机（悬停） */
    private static final int DRONE_MODE_STANDBY = 0;
    /** 无人机 mode_code：自动返航 */
    private static final int DRONE_MODE_AUTO_RTH = 9;
    /** 无人机 mode_code：降落中 */
    private static final int DRONE_MODE_LANDING = 12;

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final DockTopicSchema dockTopicSchema;
    private final ScheduledExecutorService scheduler;

    /** fly_to_point 延迟任务引用（wayline_progress + wayline_ok），fly_to_point_stop 时取消 */
    private ScheduledFuture<?> flyToPointProgressFuture;
    private ScheduledFuture<?> flyToPointOkFuture;

    public FlightCommandSimulator(SimulatorProperties props, MqttClientManager mqtt,
                                   DeviceState state, RuntimeConfig runtimeConfig,
                                   DiagnosticLogRecorder diagnosticRecorder,
                                   DockTopicSchema dockTopicSchema) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
        this.dockTopicSchema = dockTopicSchema;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flight-cmd-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    // ==================== Service 指令处理 ====================

    /**
     * 处理 fly_to_point 指令（异步双阶段确认）。
     * <p>解析 fly_to_id/max_speed/points[0]，存入 DeviceState，调度 fly_to_point_progress 事件序列。</p>
     * @param data 指令 data
     * @param bid  原始 services 指令的 bid（进度事件需保持一致）
     * @return services_reply 的 output（result=0）
     */
    public Map<String, Object> handleFlyToPoint(JsonNode data, String bid) {
        String flyToId = data.path("fly_to_id").asText();
        int maxSpeed = data.path("max_speed").asInt(10);
        JsonNode points = data.path("points");
        double targetLat = 0, targetLng = 0, targetHeight = 0;
        if (points.isArray() && !points.isEmpty()) {
            JsonNode point = points.get(0);
            targetLat = point.path("latitude").asDouble();
            targetLng = point.path("longitude").asDouble();
            targetHeight = point.path("height").asDouble();
        }

        state.setCurrentFlyToId(flyToId);
        state.setMaxSpeed(maxSpeed);
        state.setTargetLatitude(targetLat);
        state.setTargetLongitude(targetLng);
        state.setTargetHeight(targetHeight);

        log.info("fly_to_point 指令: fly_to_id={}, target=({},{},{})", flyToId, targetLat, targetLng, targetHeight);
        scheduleFlyToPointProgress(bid, flyToId, targetLat, targetLng, targetHeight);
        return Map.of("result", 0);
    }

    /**
     * 处理 fly_to_point_stop 指令（同步）。
     * <p>DJI 文档：结束 flyto 飞向目标点任务，services_reply 仅有 result，无 output。
     * 清除 DeviceState 中的 currentFlyToId，上报 fly_to_point_progress(wayline_cancel) 事件。</p>
     */
    public Map<String, Object> handleFlyToPointStop(String bid) {
        String flyToId = state.getCurrentFlyToId();
        log.info("fly_to_point_stop 指令: 结束 flyto 任务, fly_to_id={}", flyToId);
        state.setCurrentFlyToId("");

        // 取消已调度的延迟任务（wayline_progress / wayline_ok），阻止位置更新到目标点
        if (flyToPointProgressFuture != null) {
            flyToPointProgressFuture.cancel(false);
            flyToPointProgressFuture = null;
        }
        if (flyToPointOkFuture != null) {
            flyToPointOkFuture.cancel(false);
            flyToPointOkFuture = null;
        }

        // 上报 fly_to_point_progress(wayline_cancel) — 无人机在当前位置悬停
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fly_to_id", flyToId != null ? flyToId : "");
        data.put("status", "wayline_cancel");
        data.put("result", 0);
        data.put("way_point_index", 0);
        data.put("remaining_distance", 0);
        data.put("remaining_time", 0);
        data.put("planned_path_points", List.of());
        publishEvent("fly_to_point_progress", bid, data);

        return Map.of("result", 0);
    }

    /**
     * 处理 fly_to_point_update 指令（同步，无进度事件）。
     * <p>DJI 文档：更新 flyto 目标点，services_reply 仅有 result，无 output。
     * 解析 max_speed/points[0]，更新 DeviceState 中的目标点信息。</p>
     */
    public Map<String, Object> handleFlyToPointUpdate(JsonNode data) {
        int maxSpeed = data.path("max_speed").asInt(10);
        JsonNode points = data.path("points");
        double targetLat = 0, targetLng = 0, targetHeight = 0;
        if (points.isArray() && !points.isEmpty()) {
            JsonNode point = points.get(0);
            targetLat = point.path("latitude").asDouble();
            targetLng = point.path("longitude").asDouble();
            targetHeight = point.path("height").asDouble();
        }
        state.setMaxSpeed(maxSpeed);
        state.setTargetLatitude(targetLat);
        state.setTargetLongitude(targetLng);
        state.setTargetHeight(targetHeight);
        log.info("fly_to_point_update 指令: 更新目标点=({},{},{}), max_speed={}", targetLat, targetLng, targetHeight, maxSpeed);
        return Map.of("result", 0);
    }

    /**
     * 处理 takeoff_to_point 指令（异步双阶段确认）。
     * <p>解析 flight_id、target_latitude、target_longitude、target_height、max_speed，存入 DeviceState，生成 track_id，调度 takeoff_to_point_progress 事件序列。
     * 不再走通用 output.status=ok 占位（已从 ASYNC_JOB_METHODS 移除）。</p>
     * @param data 指令 data
     * @param bid  原始 services 指令的 bid
     * @return services_reply 的 output（result=0）
     */
    public Map<String, Object> handleTakeoffToPoint(JsonNode data, String bid) {
        // rth_mode=0（智能高度）拒绝：仅大疆机场不支持智能高度模式（TC-FLY-031）
        // Pilot 模式下 rth_mode=0 是合法选项（Pilot 文档标注【必填】，未说不支持）
        // 仅显式下发 rth_mode=0 才拒绝；Dock1 不下发此字段（isMissingNode）不触发
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            JsonNode rthModeNode = data.path("rth_mode");
            if (!rthModeNode.isMissingNode() && rthModeNode.asInt() == 0) {
                // DJI 文档称"大疆机场当前不支持设置返航高度模式"，但未明确真机收到 rth_mode=0 的具体反应
                // （错误码/行为），模拟器按拒绝执行返回 result=1，待真机验证
                diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "takeoff_to_point",
                        "rth_mode=0（智能高度）：DJI文档称机场不支持此模式，但未明确真机具体反应（错误码/行为），模拟器按拒绝执行返回result=1，待真机验证");
                log.warn("[M-2] takeoff_to_point rth_mode=0: 模拟器未确认真机反应，按拒绝处理");
                return Map.of("result", 1);
            }
        }

        String flightId = data.path("flight_id").asText();
        int maxSpeed = data.path("max_speed").asInt(10);
        double targetLat = data.path("target_latitude").asDouble();
        double targetLng = data.path("target_longitude").asDouble();
        double targetHeight = data.path("target_height").asDouble();
        double securityTakeoffHeight = data.path("security_takeoff_height").asDouble();
        int rthAltitude = data.path("rth_altitude").asInt();
        int rthMode = data.path("rth_mode").asInt();
        int rcLostAction = data.path("rc_lost_action").asInt();
        int commanderModeLostAction = data.path("commander_mode_lost_action").asInt();
        int commanderFlightMode = data.path("commander_flight_mode").asInt();
        double commanderFlightHeight = data.path("commander_flight_height").asDouble();
        int flightSafetyAdvanceCheck = data.path("flight_safety_advance_check").asInt();
        JsonNode simMission = data.path("simulate_mission");
        int simEnable = simMission.path("is_enable").asInt();
        double simLat = simMission.path("latitude").asDouble();
        double simLng = simMission.path("longitude").asDouble();

        String trackId = UUID.randomUUID().toString();

        state.setCurrentFlightId(flightId);
        state.setCurrentTrackId(trackId);
        state.setMaxSpeed(maxSpeed);
        state.setTargetLatitude(targetLat);
        state.setTargetLongitude(targetLng);
        state.setTargetHeight(targetHeight);
        state.setSecurityTakeoffHeight(securityTakeoffHeight);
        state.setRthAltitude(rthAltitude);
        state.setRthMode(rthMode);
        state.setRcLostAction(rcLostAction);
        state.setCommanderModeLostAction(commanderModeLostAction);
        state.setCommanderFlightMode(commanderFlightMode);
        state.setCommanderFlightHeight(commanderFlightHeight);
        state.setFlightSafetyAdvanceCheck(flightSafetyAdvanceCheck);
        state.setSimulateMissionEnable(simEnable);
        state.setSimulateMissionLatitude(simLat);
        state.setSimulateMissionLongitude(simLng);

        log.info("takeoff_to_point 指令: flight_id={}, track_id={}, target=({},{},{}), security_takeoff_height={}, rth_altitude={}, rc_lost_action={}",
                flightId, trackId, targetLat, targetLng, targetHeight, securityTakeoffHeight, rthAltitude, rcLostAction);
        scheduleTakeoffProgress(bid, flightId, trackId, targetLat, targetLng, targetHeight, securityTakeoffHeight);
        return Map.of("result", 0);
    }

    /**
     * 处理 flight_authority_grab 指令（同步，无进度事件）。
     */
    public Map<String, Object> handleFlightAuthorityGrab() {
        log.info("flight_authority_grab 指令: 飞行控制权抢夺");
        return Map.of("result", 0);
    }

    /**
     * 处理 payload_authority_grab 指令（同步，无进度事件）。
     */
    public Map<String, Object> handlePayloadAuthorityGrab(JsonNode data) {
        String payloadIndex = data.path("payload_index").asText();
        log.info("payload_authority_grab 指令: payload_index={}", payloadIndex);
        return Map.of("result", 0);
    }

    /**
     * 处理 poi_mode_enter 指令（Dock1/Pilot，同步）。
     * <p>DJI 文档：进入 POI 环绕模式，解析 latitude/longitude/height，返回 result=0。
     * 触发 poi_status_notify(status=in_progress) 事件。</p>
     */
    public Map<String, Object> handlePoiModeEnter(JsonNode data) {
        if (!isPoiSupported()) {
            log.warn("[P-10] poi_mode_enter 仅 Dock1/Pilot 支持，当前模式: {}", runtimeConfig.getDeviceMode());
            return Map.of("result", 1);
        }
        double latitude = data.path("latitude").asDouble();
        double longitude = data.path("longitude").asDouble();
        double height = data.path("height").asDouble();
        log.info("poi_mode_enter 指令: target=({},{},{})", latitude, longitude, height);
        triggerPoiStatusNotify("in_progress", 0, 0, 0, 0);
        return Map.of("result", 0);
    }

    /**
     * 处理 poi_mode_exit 指令（Dock1/Pilot，同步）。
     * <p>DJI 文档：退出 POI 环绕模式，data=null，返回 result=0。
     * 触发 poi_status_notify(status=ok) 事件。</p>
     */
    public Map<String, Object> handlePoiModeExit() {
        if (!isPoiSupported()) {
            log.warn("[P-10] poi_mode_exit 仅 Dock1/Pilot 支持，当前模式: {}", runtimeConfig.getDeviceMode());
            return Map.of("result", 1);
        }
        log.info("poi_mode_exit 指令: 退出 POI 环绕模式");
        triggerPoiStatusNotify("ok", 0, 0, 0, 0);
        return Map.of("result", 0);
    }

    /**
     * 处理 poi_circle_speed_set 指令（Dock1/Pilot，同步）。
     * <p>DJI 文档：设置 POI 环绕速度，解析 circle_speed，返回 result=0。无事件触发。</p>
     */
    public Map<String, Object> handlePoiCircleSpeedSet(JsonNode data) {
        if (!isPoiSupported()) {
            log.warn("[P-10] poi_circle_speed_set 仅 Dock1/Pilot 支持，当前模式: {}", runtimeConfig.getDeviceMode());
            return Map.of("result", 1);
        }
        double circleSpeed = data.path("circle_speed").asDouble();
        log.info("poi_circle_speed_set 指令: circle_speed={}", circleSpeed);
        return Map.of("result", 0);
    }

    /**
     * 判断当前模式是否支持 POI 环绕功能。
     * <p>DJI 文档：poi_mode_enter/exit/circle_speed_set/poi_status_notify 仅 Dock1 和 Pilot 支持，
     * Dock2/Dock3 不支持。核实依据：
     * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/dji-rc-plus-2/drc.html">Pilot drc.html</a>、
     * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html">Dock3 drc.html</a>（无 POI 指令）。</p>
     */
    private boolean isPoiSupported() {
        return runtimeConfig.getDeviceMode() == DeviceMode.PILOT
                || runtimeConfig.getDockType() == DeviceType.DOCK1;
    }

    /**
     * 统一路由指令飞行 Service 命令（由 ServiceCommandHandler 调用）。
     * @param method 指令方法名
     * @param data   指令 data
     * @param bid    原始 services 指令的 bid（进度事件需保持一致）
     * @return services_reply 的 output（含 result 字段）
     */
    public Map<String, Object> handle(String method, JsonNode data, String bid) {
        return switch (method) {
            case "fly_to_point" -> handleFlyToPoint(data, bid);
            case "fly_to_point_stop" -> handleFlyToPointStop(bid);
            case "fly_to_point_update" -> handleFlyToPointUpdate(data);
            case "takeoff_to_point" -> handleTakeoffToPoint(data, bid);
            case "flight_authority_grab" -> handleFlightAuthorityGrab();
            case "payload_authority_grab" -> handlePayloadAuthorityGrab(data);
            case "poi_mode_enter" -> handlePoiModeEnter(data);
            case "poi_mode_exit" -> handlePoiModeExit();
            case "poi_circle_speed_set" -> handlePoiCircleSpeedSet(data);
            default -> {
                log.warn("未知的指令飞行方法: {}，返回占位 result=0", method);
                yield Map.of("result", 0);
            }
        };
    }

    // ==================== 进度事件调度 ====================

    /**
     * 调度 fly_to_point_progress 事件序列：wayline_progress → wayline_ok。
     * <p>bid 与原始 services 指令一致，hivemind 据此置 ACK=SUCCESS。</p>
     */
    private void scheduleFlyToPointProgress(String bid, String flyToId,
                                             double targetLat, double targetLng, double targetHeight) {
        double startLat = state.getDroneLatitude();
        double startLng = state.getDroneLongitude();
        double startHeight = state.getDroneElevation();
        List<Map<String, Object>> pathPoints = buildPathPoints(startLat, startLng, startHeight,
                targetLat, targetLng, targetHeight, 0);
        double distance = calculateDistance(startLat, startLng, targetLat, targetLng);
        int maxSpeed = state.getMaxSpeed();
        double remainingTime = maxSpeed > 0 ? distance / maxSpeed : 0;

        // 取消上一次的延迟任务（防止 fly_to_point 连续下发时旧任务残留）
        if (flyToPointProgressFuture != null) {
            flyToPointProgressFuture.cancel(false);
        }
        if (flyToPointOkFuture != null) {
            flyToPointOkFuture.cancel(false);
        }

        // wayline_progress（执行中）
        flyToPointProgressFuture = scheduler.schedule(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fly_to_id", flyToId);
            data.put("status", "wayline_progress");
            data.put("result", 0);
            data.put("way_point_index", 0);
            data.put("remaining_distance", distance);
            data.put("remaining_time", remainingTime);
            data.put("planned_path_points", pathPoints);
            publishEvent("fly_to_point_progress", bid, data);
        }, PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // wayline_ok（完成）
        flyToPointOkFuture = scheduler.schedule(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fly_to_id", flyToId);
            data.put("status", "wayline_ok");
            data.put("result", 0);
            data.put("way_point_index", 1);
            data.put("remaining_distance", 0);
            data.put("remaining_time", 0);
            data.put("planned_path_points", pathPoints);
            publishEvent("fly_to_point_progress", bid, data);
            // simulate_mission.is_enable=1 时不更新实际位置（室内调试模式）
            if (state.getSimulateMissionEnable() != 1) {
                state.setDroneLatitude(targetLat);
                state.setDroneLongitude(targetLng);
                state.setDroneElevation(targetHeight);
            }
        }, PROGRESS_INTERVAL_SECONDS * 2, TimeUnit.SECONDS);
    }

    /**
     * 调度 takeoff_to_point_progress 事件序列：task_ready → wayline_progress → wayline_ok → task_finish。
     * @param securityTakeoffHeight 安全起飞高度（相对起飞点 ALT，m），>0 时轨迹含垂直上升阶段
     */
    private void scheduleTakeoffProgress(String bid, String flightId, String trackId,
                                          double targetLat, double targetLng, double targetHeight,
                                          double securityTakeoffHeight) {
        double startLat = state.getDroneLatitude();
        double startLng = state.getDroneLongitude();
        double startHeight = state.getDroneElevation();
        List<Map<String, Object>> pathPoints = buildPathPoints(startLat, startLng, startHeight,
                targetLat, targetLng, targetHeight, securityTakeoffHeight);
        double distance = calculateDistance(startLat, startLng, targetLat, targetLng);
        int maxSpeed = state.getMaxSpeed();
        double remainingTime = maxSpeed > 0 ? distance / maxSpeed : 0;

        // task_ready（准备起飞）
        scheduler.schedule(() -> publishTakeoffProgress(bid, flightId, trackId, "task_ready",
                0, distance, remainingTime, pathPoints), PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS);
        // wayline_progress（执行中）
        scheduler.schedule(() -> publishTakeoffProgress(bid, flightId, trackId, "wayline_progress",
                0, distance, remainingTime, pathPoints), PROGRESS_INTERVAL_SECONDS * 2, TimeUnit.SECONDS);
        // wayline_ok（到达目标点）
        scheduler.schedule(() -> publishTakeoffProgress(bid, flightId, trackId, "wayline_ok",
                1, 0, 0, pathPoints), PROGRESS_INTERVAL_SECONDS * 3, TimeUnit.SECONDS);
        // task_finish（任务完成）
        scheduler.schedule(() -> {
            publishTakeoffProgress(bid, flightId, trackId, "task_finish",
                    1, 0, 0, pathPoints);
            // simulate_mission.is_enable=1 时不更新实际位置（室内调试模式）
            if (state.getSimulateMissionEnable() != 1) {
                state.setDroneLatitude(targetLat);
                state.setDroneLongitude(targetLng);
                state.setDroneElevation(targetHeight);
            }
        }, PROGRESS_INTERVAL_SECONDS * 4, TimeUnit.SECONDS);
    }

    private void publishTakeoffProgress(String bid, String flightId, String trackId,
                                         String status, int wayPointIndex,
                                         double remainingDistance, double remainingTime,
                                         List<Map<String, Object>> pathPoints) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("result", 0);
        data.put("flight_id", flightId);
        data.put("track_id", trackId);
        data.put("way_point_index", wayPointIndex);
        data.put("remaining_distance", remainingDistance);
        data.put("remaining_time", remainingTime);
        data.put("planned_path_points", pathPoints);
        publishEvent("takeoff_to_point_progress", bid, data);
    }

    // ==================== 设备主动上报事件（REST API 触发，无前端 UI） ====================

    /**
     * 触发 obstacle_avoidance_notify 事件（仅 Dock3）。
     * @return null=成功，非 null=拒绝原因
     */
    public String triggerObstacleAvoidanceNotify(String waylineUuid, String flightId,
                                                  List<Map<String, Object>> obstacles, boolean isFinalReport) {
        if (runtimeConfig.getDockType() != DeviceType.DOCK3) {
            return "避障记录上报仅 Dock3 支持";
        }
        if (!mqtt.isConnected()) {
            return "MQTT 未连接，无法上报";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("wayline_uuid", waylineUuid);
        data.put("flight_id", flightId);
        data.put("obstacles", obstacles);
        data.put("is_final_report", isFinalReport);
        publishEvent("obstacle_avoidance_notify", UUID.randomUUID().toString(), data);
        log.info("已触发 obstacle_avoidance_notify: flight_id={}, obstacles={}", flightId, obstacles.size());
        return null;
    }

    /**
     * 触发 joystick_invalid_notify 事件（飞行控制无效原因通知，三 Dock 共有）。
     * @return null=成功，非 null=拒绝原因
     */
    public String triggerJoystickInvalidNotify(int reason) {
        if (!mqtt.isConnected()) {
            return "MQTT 未连接，无法上报";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        publishEvent("joystick_invalid_notify", UUID.randomUUID().toString(), data);
        log.info("已触发 joystick_invalid_notify: reason={}", reason);
        return null;
    }

    /**
     * 触发 camera_photo_take_progress 事件（全景拍照进度，三 Dock 共有）。
     * @return null=成功，非 null=拒绝原因
     */
    public String triggerCameraPhotoTakeProgress(String status, int currentStep, int percent, int cameraMode) {
        if (!mqtt.isConnected()) {
            return "MQTT 未连接，无法上报";
        }
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("camera_mode", cameraMode);

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("current_step", currentStep);
        progress.put("percent", percent);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", status);
        output.put("progress", progress);
        output.put("ext", ext);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("output", output);
        data.put("result", 0);

        publishEvent("camera_photo_take_progress", UUID.randomUUID().toString(), data);
        log.info("已触发 camera_photo_take_progress: status={}, percent={}", status, percent);
        return null;
    }

    /**
     * 触发 poi_status_notify 事件（Dock1/Pilot）。
     * @return null=成功，非 null=拒绝原因
     */
    public String triggerPoiStatusNotify(String status, int reason,
                                          double circleRadius, double circleSpeed, double maxCircleSpeed) {
        if (!isPoiSupported()) {
            return "POI 环绕状态通知仅 Dock1/Pilot 支持";
        }
        if (!mqtt.isConnected()) {
            return "MQTT 未连接，无法上报";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("reason", reason);
        data.put("circle_radius", circleRadius);
        data.put("circle_speed", circleSpeed);
        data.put("max_circle_speed", maxCircleSpeed);
        publishEvent("poi_status_notify", UUID.randomUUID().toString(), data);
        log.info("已触发 poi_status_notify: status={}, reason={}", status, reason);
        return null;
    }

    /**
     * 触发遥控器失联行为（模拟 rc_lost_action）。
     * <p>根据 takeoff_to_point 下发的 rc_lost_action 执行对应行为（TC-FLY-028）：
     * <ul>
     *   <li>0=悬停：mode_code=0，位置不变</li>
     *   <li>1=降落：mode_code=12（降落中），延迟后 height=0、mode_code=0、droneInDock=false（原地降落）</li>
     *   <li>2=返航：mode_code=9（自动返航），延迟后位置=机场、mode_code=0、droneInDock=true（归舱）</li>
     * </ul>
     * 触发时立即上报 joystick_invalid_notify 事件（reason=0 遥控器失联，need_reply=1）。
     * 不发 return_home_info（该事件属于航线管理，rc_lost 走 joystick_invalid_notify 通知链路）。</p>
     * @return null=成功，非 null=拒绝原因
     */
    public String triggerRcLost() {
        if (!mqtt.isConnected()) {
            return "MQTT 未连接，无法模拟失联";
        }
        int rcLostAction = state.getRcLostAction();

        // 上报 joystick_invalid_notify 事件（reason=0 遥控器失联）
        Map<String, Object> notifyData = new LinkedHashMap<>();
        notifyData.put("reason", 0);
        publishEvent("joystick_invalid_notify", UUID.randomUUID().toString(), notifyData);

        // 根据 rc_lost_action 设置 mode_code 并调度后续行为
        int targetModeCode = switch (rcLostAction) {
            case 0 -> DRONE_MODE_STANDBY;
            case 1 -> DRONE_MODE_LANDING;
            case 2 -> DRONE_MODE_AUTO_RTH;
            default -> DRONE_MODE_STANDBY;
        };
        state.setDroneModeCode(targetModeCode);

        switch (rcLostAction) {
            case 1 -> {
                scheduler.schedule(this::completeRcLostLanding,
                        RC_LOST_DELAY_SECONDS, TimeUnit.SECONDS);
                // M-2：降落位置和后续状态未得到 DJI 文档确认，待真机验证
                diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "trigger_rc_lost",
                        "rc_lost_action=1(降落)：原地降落(保持经纬度, height=0, mode_code=0, droneInDock=false)，DJI文档未明确降落位置和后续状态，待真机验证");
            }
            case 2 -> {
                scheduler.schedule(this::completeRcLostReturnHome,
                        RC_LOST_DELAY_SECONDS, TimeUnit.SECONDS);
                // M-2：不发return_home_info + 延迟归舱行为未得到 DJI 文档确认，待真机验证
                diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "trigger_rc_lost",
                        "rc_lost_action=2(返航)：不发return_home_info(属航线管理事件) + 延迟归舱(mode_code=9→5s→位置=机场, inDock=true)，DJI文档未明确，待真机验证");
            }
            default -> {} // 悬停：无后续行为
        }

        log.info("模拟遥控器失联: rc_lost_action={}, mode_code={}", rcLostAction, targetModeCode);
        return null;
    }

    /**
     * 失联降落完成：原地降落，高度归零，无人机不在舱内。
     */
    private void completeRcLostLanding() {
        state.setDroneModeCode(DRONE_MODE_STANDBY);
        state.setDroneHeight(0.0);
        state.setDroneInDock(false);
        log.info("失联降落完成: 原地降落, droneInDock=false");
    }

    /**
     * 失联返航完成：飞回机场归舱，恢复 dock 待机状态。
     */
    private void completeRcLostReturnHome() {
        state.setDroneModeCode(DRONE_MODE_STANDBY);
        state.setDroneInDock(true);
        state.setDroneChargeState(1);
        state.setCoverOpen(false);
        state.setPutterExpanded(false);
        state.setDockModeCode(0);
        state.setDroneLatitude(runtimeConfig.getLocationLatitude());
        state.setDroneLongitude(runtimeConfig.getLocationLongitude());
        state.setDroneHeight(0.0);
        log.info("失联返航完成: 已回到机场位置, droneInDock=true");
    }

    // ==================== 工具方法 ====================

    /**
     * 构造 planned_path_points。
     * <p>无安全起飞高度时：起点 → 目标点（2 个点）。
     * 有安全起飞高度时（takeoff_to_point）：起点 → 安全起飞点（同经纬度，上升至安全高度）→ 目标点（3 个点）。
     * 安全起飞高度为相对高度（ALT），需叠加起飞点椭球高转换为椭球高。</p>
     * @param securityTakeoffHeight 安全起飞高度（相对起飞点 ALT，m），<=0 时不插入中间点
     */
    private List<Map<String, Object>> buildPathPoints(double startLat, double startLng, double startHeight,
                                                       double targetLat, double targetLng, double targetHeight,
                                                       double securityTakeoffHeight) {
        List<Map<String, Object>> points = new ArrayList<>();
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("latitude", startLat);
        start.put("longitude", startLng);
        start.put("height", startHeight);
        points.add(start);
        if (securityTakeoffHeight > 0) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("latitude", startLat);
            safe.put("longitude", startLng);
            safe.put("height", startHeight + securityTakeoffHeight);
            points.add(safe);
        }
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("latitude", targetLat);
        end.put("longitude", targetLng);
        end.put("height", targetHeight);
        points.add(end);
        return points;
    }

    /**
     * 粗略计算两点间水平距离（m），1° ≈ 111km。
     */
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = lat2 - lat1;
        double dLng = lng2 - lng1;
        return Math.sqrt(dLat * dLat + dLng * dLng) * 111000;
    }

    /**
     * 发布 events 事件。
     * <p>格式：{@code {bid, tid, timestamp, need_reply, gateway, method, data}}</p>
     */
    private void publishEvent(String method, String bid, Map<String, Object> data) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("bid", bid);
            envelope.put("tid", UUID.randomUUID().toString());
            envelope.put("timestamp", System.currentTimeMillis());
            // DJI 指令飞行事件信封必填：1=需要答复（fly_to_point_progress/takeoff_to_point_progress/
            // obstacle_avoidance_notify/joystick_invalid_notify/camera_photo_take_progress 文档均为 need_reply=1）
            envelope.put("need_reply", 1);
            envelope.put("gateway", runtimeConfig.getGatewaySn());
            envelope.put("method", method);
            envelope.put("data", data);

            String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getGatewaySn());
            mqtt.publishJson(topic, envelope);
            log.info("已发布 events: method={}, bid={}", method, bid);
        } catch (Exception e) {
            log.error("发布 events 失败: method={}, bid={}, err={}", method, bid, e.getMessage(), e);
        }
    }
}
