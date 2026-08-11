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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 指令飞行模拟器（drc.html）。
 * <p>处理 DJI Cloud API「指令飞行」协议（走 services/events topic），与
 * {@link DrcCommandHandler}（远程控制 remote-control.html，走 drc/down/drc/up）是两套独立协议。
 * <p>职责：
 * <ul>
 *   <li>Service 指令：fly_to_point / takeoff_to_point（异步双阶段确认）、flight_authority_grab / payload_authority_grab（同步）</li>
 *   <li>进度事件：fly_to_point_progress / takeoff_to_point_progress（bid 与原始 services 一致）</li>
 *   <li>设备主动上报事件：obstacle_avoidance_notify（仅 Dock3）、joystick_invalid_notify、camera_photo_take_progress、poi_status_notify（仅 Dock1）</li>
 * </ul>
 * <p>详见 DJI Cloud API <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html">指令飞行</a>。
 */
@Component
public class FlightCommandSimulator {

    private static final Logger log = LoggerFactory.getLogger(FlightCommandSimulator.class);

    /** 进度事件间隔（秒） */
    private static final long PROGRESS_INTERVAL_SECONDS = 2;

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final RuntimeConfig runtimeConfig;
    private final ScheduledExecutorService scheduler;

    public FlightCommandSimulator(SimulatorProperties props, MqttClientManager mqtt,
                                   DeviceState state, RuntimeConfig runtimeConfig) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.runtimeConfig = runtimeConfig;
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
     * 处理 takeoff_to_point 指令（异步双阶段确认）。
     * <p>解析 flight_id、target_latitude、target_longitude、target_height、max_speed，存入 DeviceState，生成 track_id，调度 takeoff_to_point_progress 事件序列。
     * 不再走通用 output.status=ok 占位（已从 ASYNC_JOB_METHODS 移除）。</p>
     * @param data 指令 data
     * @param bid  原始 services 指令的 bid
     * @return services_reply 的 output（result=0）
     */
    public Map<String, Object> handleTakeoffToPoint(JsonNode data, String bid) {
        String flightId = data.path("flight_id").asText();
        int maxSpeed = data.path("max_speed").asInt(10);
        double targetLat = data.path("target_latitude").asDouble();
        double targetLng = data.path("target_longitude").asDouble();
        double targetHeight = data.path("target_height").asDouble();

        String trackId = UUID.randomUUID().toString();

        state.setCurrentFlightId(flightId);
        state.setCurrentTrackId(trackId);
        state.setMaxSpeed(maxSpeed);
        state.setTargetLatitude(targetLat);
        state.setTargetLongitude(targetLng);
        state.setTargetHeight(targetHeight);

        log.info("takeoff_to_point 指令: flight_id={}, track_id={}, target=({},{},{})",
                flightId, trackId, targetLat, targetLng, targetHeight);
        scheduleTakeoffProgress(bid, flightId, trackId, targetLat, targetLng, targetHeight);
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
     * 统一路由指令飞行 Service 命令（由 ServiceCommandHandler 调用）。
     * @param method 指令方法名
     * @param data   指令 data
     * @param bid    原始 services 指令的 bid（进度事件需保持一致）
     * @return services_reply 的 output（含 result 字段）
     */
    public Map<String, Object> handle(String method, JsonNode data, String bid) {
        return switch (method) {
            case "fly_to_point" -> handleFlyToPoint(data, bid);
            case "takeoff_to_point" -> handleTakeoffToPoint(data, bid);
            case "flight_authority_grab" -> handleFlightAuthorityGrab();
            case "payload_authority_grab" -> handlePayloadAuthorityGrab(data);
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
                targetLat, targetLng, targetHeight);
        double distance = calculateDistance(startLat, startLng, targetLat, targetLng);
        int maxSpeed = state.getMaxSpeed();
        double remainingTime = maxSpeed > 0 ? distance / maxSpeed : 0;

        // wayline_progress（执行中）
        scheduler.schedule(() -> {
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
        scheduler.schedule(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fly_to_id", flyToId);
            data.put("status", "wayline_ok");
            data.put("result", 0);
            data.put("way_point_index", 1);
            data.put("remaining_distance", 0);
            data.put("remaining_time", 0);
            data.put("planned_path_points", pathPoints);
            publishEvent("fly_to_point_progress", bid, data);
        }, PROGRESS_INTERVAL_SECONDS * 2, TimeUnit.SECONDS);
    }

    /**
     * 调度 takeoff_to_point_progress 事件序列：task_ready → wayline_progress → wayline_ok → task_finish。
     */
    private void scheduleTakeoffProgress(String bid, String flightId, String trackId,
                                          double targetLat, double targetLng, double targetHeight) {
        double startLat = state.getDroneLatitude();
        double startLng = state.getDroneLongitude();
        double startHeight = state.getDroneElevation();
        List<Map<String, Object>> pathPoints = buildPathPoints(startLat, startLng, startHeight,
                targetLat, targetLng, targetHeight);
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
        scheduler.schedule(() -> publishTakeoffProgress(bid, flightId, trackId, "task_finish",
                1, 0, 0, pathPoints), PROGRESS_INTERVAL_SECONDS * 4, TimeUnit.SECONDS);
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
     * 触发 poi_status_notify 事件（仅 Dock1）。
     * @return null=成功，非 null=拒绝原因
     */
    public String triggerPoiStatusNotify(String status, int reason,
                                          double circleRadius, double circleSpeed, double maxCircleSpeed) {
        if (runtimeConfig.getDockType() != DeviceType.DOCK1) {
            return "POI 环绕状态通知仅 Dock1 支持";
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

    // ==================== 工具方法 ====================

    /**
     * 构造 planned_path_points（起飞点 + 目标点）。
     */
    private List<Map<String, Object>> buildPathPoints(double startLat, double startLng, double startHeight,
                                                       double targetLat, double targetLng, double targetHeight) {
        List<Map<String, Object>> points = new ArrayList<>();
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("latitude", startLat);
        start.put("longitude", startLng);
        start.put("height", startHeight);
        points.add(start);
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
     * <p>格式：{@code {bid, data, tid, timestamp, method}}</p>
     */
    private void publishEvent(String method, String bid, Map<String, Object> data) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("bid", bid);
            envelope.put("data", data);
            envelope.put("tid", UUID.randomUUID().toString());
            envelope.put("timestamp", System.currentTimeMillis());
            envelope.put("method", method);

            String topic = TopicConstants.topic(TopicConstants.EVENTS, runtimeConfig.getDockSn());
            mqtt.publishJson(topic, envelope);
            log.info("已发布 events: method={}, bid={}", method, bid);
        } catch (Exception e) {
            log.error("发布 events 失败: method={}, bid={}, err={}", method, bid, e.getMessage(), e);
        }
    }
}
