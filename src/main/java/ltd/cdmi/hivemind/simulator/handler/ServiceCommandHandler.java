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
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.ProtocolValidator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.TopicConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * services 下行命令路由与应答处理器。
 * <p>订阅 thing/product/{sn}/services，按 method 路由到对应处理器，统一回 services_reply。</p>
 * <p>未实现的命令统一回 result=0 占位（临时占位项目，保证云端不报错）。</p>
 */
@Component
public class ServiceCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ServiceCommandHandler.class);

    /** 航线任务相关命令（委托 WaylineTaskSimulator，步骤6注入） */
    private static final Set<String> WAYLINE_METHODS = Set.of(
            "flighttask_prepare", "flighttask_execute", "flighttask_pause",
            "flighttask_recovery", "flighttask_undo", "flighttask_stop",
            "return_home", "return_home_cancel", "return_specific_home",
            "flight_setup_abort"
    );

    /** 直播相关命令（委托 LiveStreamSimulator，步骤7注入） */
    private static final Set<String> LIVE_METHODS = Set.of(
            "live_start_push", "live_stop_push", "live_set_quality",
            "live_camera_change", "live_lens_change"
    );

    /** 媒体管理相关命令（委托 MediaUploadSimulator） */
    private static final Set<String> MEDIA_METHODS = Set.of(
            "upload_flighttask_media_prioritize"
    );

    /** DRC 远程控制命令 */
    private static final Set<String> DRC_METHODS = Set.of(
            "drc_mode_enter", "drc_mode_exit"
    );

    /** 指令飞行命令（drc.html，委托 FlightCommandSimulator） */
    private static final Set<String> FLY_METHODS = Set.of(
            "fly_to_point", "fly_to_point_stop", "fly_to_point_update",
            "takeoff_to_point",
            "flight_authority_grab", "payload_authority_grab",
            "poi_mode_enter", "poi_mode_exit", "poi_circle_speed_set"
    );

    /** 负载控制命令（drc.html，同步指令，本类直接处理） */
    private static final Set<String> PAYLOAD_METHODS = Set.of(
            "camera_frame_zoom", "camera_mode_switch",
            "camera_photo_take", "camera_photo_stop",
            "camera_recording_start", "camera_recording_stop",
            "camera_screen_drag", "camera_aim",
            "camera_focal_length_set", "gimbal_reset",
            "camera_look_at", "camera_screen_split",
            "photo_storage_set", "video_storage_set",
            "camera_exposure_mode_set", "camera_exposure_set", "camera_focus_mode_set",
            "camera_focus_value_set", "camera_point_focus_action",
            "ir_metering_mode_set", "ir_metering_point_set", "ir_metering_area_set"
    );

    /**
     * 异步指令双阶段确认已迁移至 RemoteDebugSimulator（远程调试 Job 指令）和 FlightCommandSimulator（指令飞行）。
     * 此处不再维护 ASYNC_JOB_METHODS 集合。
     */

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final ObjectMapper objectMapper;
    private final FlightCommandSimulator flightCommandSimulator;
    private final RemoteDebugSimulator remoteDebugSimulator;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final CoverageRecorder coverageRecorder;
    private final RuntimeConfig runtimeConfig;

    /** 动态注册的命令处理器：method → (data, tid, bid) → output */
    private BiFunction<String, JsonNode, Map<String, Object>> waylineHandler;
    private BiFunction<String, JsonNode, Map<String, Object>> liveHandler;
    private BiFunction<String, JsonNode, Map<String, Object>> mediaHandler;

    public ServiceCommandHandler(SimulatorProperties props, MqttClientManager mqtt,
                                 DeviceState state, ObjectMapper objectMapper,
                                 FlightCommandSimulator flightCommandSimulator,
                                 RemoteDebugSimulator remoteDebugSimulator,
                                 DiagnosticLogRecorder diagnosticRecorder,
                                 CoverageRecorder coverageRecorder,
                                 RuntimeConfig runtimeConfig) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.flightCommandSimulator = flightCommandSimulator;
        this.remoteDebugSimulator = remoteDebugSimulator;
        this.diagnosticRecorder = diagnosticRecorder;
        this.coverageRecorder = coverageRecorder;
        this.runtimeConfig = runtimeConfig;
    }

    @PostConstruct
    public void init() {
        String dockSn = runtimeConfig.getDockSn();
        mqtt.addListener(TopicConstants.topic(TopicConstants.SERVICES, dockSn), this::handleService);
        log.info("ServiceCommandHandler 已注册监听: {}", TopicConstants.topic(TopicConstants.SERVICES, dockSn));
    }

    /**
     * 注册航线任务处理器（由 WaylineTaskSimulator 在步骤6调用）。
     */
    public void setWaylineHandler(BiFunction<String, JsonNode, Map<String, Object>> handler) {
        this.waylineHandler = handler;
    }

    /**
     * 注册直播处理器（由 LiveStreamSimulator 在步骤7调用）。
     */
    public void setLiveHandler(BiFunction<String, JsonNode, Map<String, Object>> handler) {
        this.liveHandler = handler;
    }

    /**
     * 注册媒体上传处理器（由 MediaUploadSimulator 在步骤7调用）。
     */
    public void setMediaHandler(BiFunction<String, JsonNode, Map<String, Object>> handler) {
        this.mediaHandler = handler;
    }

    private void handleService(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String method = node.path("method").asText();
            String tid = node.path("tid").asText();
            String bid = node.path("bid").asText();
            JsonNode data = node.path("data");

            // P-6/P-7：主动校验必填字段和字段类型（不阻塞流程，仅记录诊断）
            DiagnosticCode fieldError = ProtocolValidator.validateFields(node);
            if (fieldError != null) {
                log.error("{} services 消息字段校验失败: method={}", ProtocolValidator.logPrefix(fieldError), method);
                diagnosticRecorder.record(fieldError, method, "services 消息字段校验失败");
            }

            // 覆盖率统计：记录平台下发的 services method（按当前 MQTT 地址归档）
            coverageRecorder.record(runtimeConfig.getMqttHost() + ":" + runtimeConfig.getMqttPort(), method);

            log.info("收到 services 命令: method={}, tid={}", method, tid);

            Map<String, Object> output;
            try {
                output = routeCommand(method, data, bid);
            } catch (Exception e) {
                DiagnosticCode code = ProtocolValidator.classifyException(e);
                log.error("{} 命令处理异常 method={}: {}", ProtocolValidator.logPrefix(code), method, e.getMessage(), e);
                diagnosticRecorder.record(code, method, "命令处理异常: " + e.getMessage());
                output = Map.of("result", 1);
            }

            publishServiceReply(method, tid, bid, output);

            // 异步指令的双阶段确认（events 进度事件）由各专用 Simulator 内部调度：
            // - RemoteDebugSimulator：远程调试 Job 指令（cover_open/drone_open 等）
            // - FlightCommandSimulator：指令飞行（fly_to_point/takeoff_to_point 等）
            // 此处不再统一调度。
        } catch (Exception e) {
            DiagnosticCode code = ProtocolValidator.classifyException(e);
            log.error("{} 处理 services 消息失败: {}", ProtocolValidator.logPrefix(code), e.getMessage(), e);
            diagnosticRecorder.record(code, "-", "处理 services 消息失败: " + e.getMessage());
        }
    }

    /**
     * 按 method 路由到对应处理器。返回 output（含 result 字段）。
     */
    private Map<String, Object> routeCommand(String method, JsonNode data, String bid) {
        // 航线任务命令
        if (WAYLINE_METHODS.contains(method)) {
            if (waylineHandler != null) {
                return waylineHandler.apply(method, data);
            }
            log.warn("航线命令 {} 未注册处理器，返回占位 result=0", method);
            return Map.of("result", 0);
        }
        // 直播命令
        if (LIVE_METHODS.contains(method)) {
            if (liveHandler != null) {
                return liveHandler.apply(method, data);
            }
            log.warn("直播命令 {} 未注册处理器，返回占位 result=0", method);
            return Map.of("result", 0);
        }
        // 媒体上传命令
        if (MEDIA_METHODS.contains(method)) {
            if (mediaHandler != null) {
                return mediaHandler.apply(method, data);
            }
            return Map.of("result", 0);
        }
        // DRC 远程控制命令
        if (DRC_METHODS.contains(method)) {
            return handleDrcCommand(method);
        }

        // 指令飞行命令（drc.html）
        if (FLY_METHODS.contains(method)) {
            return flightCommandSimulator.handle(method, data, bid);
        }

        // 负载控制命令（drc.html）
        if (PAYLOAD_METHODS.contains(method)) {
            return handlePayloadCommand(method, data);
        }

        // 远程调试命令（cmd.html）
        if (RemoteDebugSimulator.isRemoteDebugMethod(method)) {
            return remoteDebugSimulator.handle(method, data, bid);
        }

        // 其他未实现的命令：统一占位 result=0
        log.warn("[S-2] 未覆盖指令占位应答: method={}", method);
        diagnosticRecorder.record(DiagnosticCode.SIMULATOR_METHOD_NOT_IMPLEMENTED, method, "未覆盖指令占位应答");
        return Map.of("result", 0);
    }

    /**
     * 处理 DRC 远程控制命令。
     * <p>drc_mode_enter：进入 DRC 模式，设 drcState=2(已连接)。</p>
     * <p>drc_mode_exit：退出 DRC 模式，设 drcState=0(空闲)。</p>
     */
    private Map<String, Object> handleDrcCommand(String method) {
        switch (method) {
            case "drc_mode_enter" -> {
                state.setDrcState(2);
                log.info("已进入 DRC 模式");
                publishDrcState(2);
            }
            case "drc_mode_exit" -> {
                state.setDrcState(0);
                log.info("已退出 DRC 模式");
                publishDrcState(0);
            }
        }
        return Map.of("result", 0);
    }

    /**
     * 处理负载控制命令（drc.html）。
     * <p>DJI 文档：负载控制指令的 services_reply 均仅有 result，无 output（camera_photo_take 全景模式除外）。
     * camera_mode_switch 额外更新 DeviceState.cameraMode。</p>
     */
    private Map<String, Object> handlePayloadCommand(String method, JsonNode data) {
        // P-10：枚举值校验，非法枚举值返回 result=1（按设计文档"命令处理失败返回 result=1"约定）
        DiagnosticCode enumError = ProtocolValidator.validatePayloadEnum(method, data);
        if (enumError != null) {
            log.error("{} 负载控制指令枚举值校验失败: method={}", ProtocolValidator.logPrefix(enumError), method);
            diagnosticRecorder.record(enumError, method, "枚举值校验失败");
            return Map.of("result", 1);
        }

        switch (method) {
            case "camera_frame_zoom" -> {
                String payloadIndex = data.path("payload_index").asText();
                String cameraType = data.path("camera_type").asText();
                boolean locked = data.path("locked").asBoolean();
                double x = data.path("x").asDouble();
                double y = data.path("y").asDouble();
                double width = data.path("width").asDouble();
                double height = data.path("height").asDouble();
                log.info("camera_frame_zoom 指令: payload_index={}, camera_type={}, locked={}, x={}, y={}, width={}, height={}",
                        payloadIndex, cameraType, locked, x, y, width, height);
            }
            case "camera_mode_switch" -> {
                String payloadIndex = data.path("payload_index").asText();
                int cameraMode = data.path("camera_mode").asInt();
                state.setPayloadIndex(payloadIndex);
                state.setCameraMode(cameraMode);
                log.info("camera_mode_switch 指令: payload_index={}, camera_mode={}", payloadIndex, cameraMode);
            }
            case "camera_photo_take" -> {
                String payloadIndex = data.path("payload_index").asText();
                log.info("camera_photo_take 指令: payload_index={}, camera_mode={}", payloadIndex, state.getCameraMode());
                // DJI 协议枚举 camera_mode: 0=拍照, 1=录像, 2=智能低光, 3=全景拍照
                // 全景拍照为持续性拍照行为，services_reply 需返回 output.status=in_progress，
                // 表示后续会有 camera_photo_take_progress 事件上报
                if (state.getCameraMode() == 3) {
                    log.info("全景拍照模式，services_reply 包含 output.status=in_progress");
                    return Map.of("result", 0, "output", Map.of("status", "in_progress"));
                }
            }
            case "camera_photo_stop" -> {
                String payloadIndex = data.path("payload_index").asText();
                log.info("camera_photo_stop 指令: payload_index={}", payloadIndex);
            }
            case "camera_recording_start" -> {
                String payloadIndex = data.path("payload_index").asText();
                log.info("camera_recording_start 指令: payload_index={}", payloadIndex);
            }
            case "camera_recording_stop" -> {
                String payloadIndex = data.path("payload_index").asText();
                log.info("camera_recording_stop 指令: payload_index={}", payloadIndex);
            }
            case "camera_screen_drag" -> {
                String payloadIndex = data.path("payload_index").asText();
                boolean locked = data.path("locked").asBoolean();
                double pitchSpeed = data.path("pitch_speed").asDouble();
                double yawSpeed = data.path("yaw_speed").asDouble();
                log.info("camera_screen_drag 指令: payload_index={}, locked={}, pitch_speed={}, yaw_speed={}",
                        payloadIndex, locked, pitchSpeed, yawSpeed);
            }
            case "camera_aim" -> {
                String payloadIndex = data.path("payload_index").asText();
                String cameraType = data.path("camera_type").asText();
                boolean locked = data.path("locked").asBoolean();
                double x = data.path("x").asDouble();
                double y = data.path("y").asDouble();
                log.info("camera_aim 指令: payload_index={}, camera_type={}, locked={}, x={}, y={}",
                        payloadIndex, cameraType, locked, x, y);
            }
            case "camera_focal_length_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                String cameraType = data.path("camera_type").asText();
                double zoomFactor = data.path("zoom_factor").asDouble();
                log.info("camera_focal_length_set 指令: payload_index={}, camera_type={}, zoom_factor={}",
                        payloadIndex, cameraType, zoomFactor);
            }
            case "gimbal_reset" -> {
                String payloadIndex = data.path("payload_index").asText();
                int resetMode = data.path("reset_mode").asInt();
                log.info("gimbal_reset 指令: payload_index={}, reset_mode={}", payloadIndex, resetMode);
            }
            case "camera_look_at" -> {
                String payloadIndex = data.path("payload_index").asText();
                boolean locked = data.path("locked").asBoolean();
                double latitude = data.path("latitude").asDouble();
                double longitude = data.path("longitude").asDouble();
                double height = data.path("height").asDouble();
                log.info("camera_look_at 指令: payload_index={}, locked={}, target=({},{},{})",
                        payloadIndex, locked, latitude, longitude, height);
            }
            case "camera_screen_split" -> {
                String payloadIndex = data.path("payload_index").asText();
                boolean enable = data.path("enable").asBoolean();
                log.info("camera_screen_split 指令: payload_index={}, enable={}", payloadIndex, enable);
            }
            case "photo_storage_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                List<String> settings = new ArrayList<>();
                data.path("photo_storage_settings").forEach(n -> settings.add(n.asText()));
                log.info("photo_storage_set 指令: payload_index={}, settings={}", payloadIndex, settings);
            }
            case "video_storage_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                List<String> settings = new ArrayList<>();
                data.path("video_storage_settings").forEach(n -> settings.add(n.asText()));
                log.info("video_storage_set 指令: payload_index={}, settings={}", payloadIndex, settings);
            }
            case "camera_exposure_mode_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                String cameraType = data.path("camera_type").asText();
                int exposureMode = data.path("exposure_mode").asInt();
                log.info("camera_exposure_mode_set 指令: payload_index={}, camera_type={}, exposure_mode={}",
                        payloadIndex, cameraType, exposureMode);
            }
            case "camera_exposure_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                String cameraType = data.path("camera_type").asText();
                String exposureValue = data.path("exposure_value").asText();
                log.info("camera_exposure_set 指令: payload_index={}, camera_type={}, exposure_value={}",
                        payloadIndex, cameraType, exposureValue);
            }
            case "camera_focus_mode_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                String cameraType = data.path("camera_type").asText();
                int focusMode = data.path("focus_mode").asInt();
                log.info("camera_focus_mode_set 指令: payload_index={}, camera_type={}, focus_mode={}",
                        payloadIndex, cameraType, focusMode);
            }
            case "camera_focus_value_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                String cameraType = data.path("camera_type").asText();
                int focusValue = data.path("focus_value").asInt();
                log.info("camera_focus_value_set 指令: payload_index={}, camera_type={}, focus_value={}",
                        payloadIndex, cameraType, focusValue);
            }
            case "camera_point_focus_action" -> {
                String payloadIndex = data.path("payload_index").asText();
                String cameraType = data.path("camera_type").asText();
                double x = data.path("x").asDouble();
                double y = data.path("y").asDouble();
                log.info("camera_point_focus_action 指令: payload_index={}, camera_type={}, x={}, y={}",
                        payloadIndex, cameraType, x, y);
            }
            case "ir_metering_mode_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                int mode = data.path("mode").asInt();
                log.info("ir_metering_mode_set 指令: payload_index={}, mode={}", payloadIndex, mode);
            }
            case "ir_metering_point_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                double x = data.path("x").asDouble();
                double y = data.path("y").asDouble();
                log.info("ir_metering_point_set 指令: payload_index={}, x={}, y={}", payloadIndex, x, y);
            }
            case "ir_metering_area_set" -> {
                String payloadIndex = data.path("payload_index").asText();
                double x = data.path("x").asDouble();
                double y = data.path("y").asDouble();
                double width = data.path("width").asDouble();
                double height = data.path("height").asDouble();
                log.info("ir_metering_area_set 指令: payload_index={}, x={}, y={}, width={}, height={}",
                        payloadIndex, x, y, width, height);
            }
        }
        return Map.of("result", 0);
    }

    /**
     * 通过 state topic 上报 DRC 状态变更。
     * <p>DJI Cloud API 规范：drc_state 通过 thing/product/{gateway_sn}/state 上报。</p>
     * <p>格式：{@code {tid, bid, timestamp, gateway, data:{"drc_state":N}}}</p>
     */
    private void publishDrcState(int drcState) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("drc_state", drcState);

        Map<String, Object> stateMsg = new LinkedHashMap<>();
        stateMsg.put("tid", UUID.randomUUID().toString());
        stateMsg.put("bid", UUID.randomUUID().toString());
        stateMsg.put("timestamp", System.currentTimeMillis());
        stateMsg.put("gateway", runtimeConfig.getDockSn());
        stateMsg.put("data", data);

        String stateTopic = TopicConstants.topic(TopicConstants.STATE, runtimeConfig.getDockSn());
        mqtt.publishJson(stateTopic, stateMsg);
        log.info("已上报 DRC 状态: drc_state={} via state topic", drcState);
    }

    /**
     * 发布 services_reply。
     * <p>格式：{@code {tid, bid, timestamp, gateway, method, data:{result, output}}}</p>
     */
    private void publishServiceReply(String method, String tid, String bid, Map<String, Object> output) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", output.getOrDefault("result", 0));
        if (output.containsKey("output")) {
            data.put("output", output.get("output"));
        }

        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("tid", tid);
        reply.put("bid", bid);
        reply.put("timestamp", System.currentTimeMillis());
        reply.put("gateway", runtimeConfig.getDockSn());
        reply.put("method", method);
        reply.put("data", data);

        String replyTopic = TopicConstants.topic(TopicConstants.SERVICES_REPLY, runtimeConfig.getDockSn());
        mqtt.publishJson(replyTopic, reply);
        log.info("已回复 services_reply: method={}, tid={}, result={}", method, tid, data.get("result"));
    }
}
