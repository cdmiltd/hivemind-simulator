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
import jakarta.annotation.PostConstruct;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.ProtocolValidator;
import ltd.cdmi.hivemind.simulator.mqtt.DrcProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负载控制指令处理器（按 controllerType 路由）。
 * <p>提取自 {@link ServiceCommandHandler} 的负载控制逻辑，统一管理 camera/gimbal/ir 等负载指令。
 * <p>路由策略由 {@link DrcProtocol} 决定：
 * <ul>
 *   <li>RC Pro 行业版 / Dock：负载控制走 services 通道，方法名无 {@code drc_} 前缀（如 {@code camera_aim}）。
 *       由 {@link ServiceCommandHandler} 委托本处理器，无需额外注册。</li>
 *   <li>RC Plus 2 行业版：负载控制走 DRC 通道，方法名有 {@code drc_} 前缀（如 {@code drc_camera_aim}）。
 *       本处理器在 {@code @PostConstruct} 时向 {@link DrcCommandHandler} 注册 drc_ 前缀处理器。</li>
 * </ul>
 * <p>消息格式差异由各通道处理器封装，本处理器仅返回 output（含 result 字段）：
 * <ul>
 *   <li>services 通道：{@link ServiceCommandHandler} 封装为 {@code {tid, bid, method, data:{result, output}}}</li>
 *   <li>DRC 通道：{@link DrcCommandHandler} 封装为 {@code {method, data:{result,...}, seq}}</li>
 * </ul>
 * <p>核实依据：DJI Cloud API Pilot 上云指令飞行文档（RC Pro 行业版 vs RC Plus 2 行业版）。
 */
@Component
public class PayloadControlHandler {

    private static final Logger log = LoggerFactory.getLogger(PayloadControlHandler.class);

    /**
     * 负载控制命令基础方法名集合（不含 drc_ 前缀）。
     * <p>ServiceCommandHandler 用此集合判断是否委托给本处理器；
     * Pilot 模式下用此集合向 DrcCommandHandler 注册 drc_ 前缀处理器。
     */
    public static final Set<String> PAYLOAD_METHODS = Set.of(
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

    private final DeviceState state;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final RuntimeConfig runtimeConfig;
    private final DrcCommandHandler drcCommandHandler;

    public PayloadControlHandler(DeviceState state,
                                  DiagnosticLogRecorder diagnosticRecorder,
                                  RuntimeConfig runtimeConfig,
                                  DrcCommandHandler drcCommandHandler) {
        this.state = state;
        this.diagnosticRecorder = diagnosticRecorder;
        this.runtimeConfig = runtimeConfig;
        this.drcCommandHandler = drcCommandHandler;
    }

    /**
     * Pilot 模式下，根据 DrcProtocol 向 DrcCommandHandler 注册 drc_ 前缀的负载控制处理器。
     * <p>RC Plus 2 行业版的负载控制指令通过 DRC 通道下发（方法名有 drc_ 前缀），
     * 需向 DrcCommandHandler 注册以接收和回复这些指令。
     * <p>RC Pro 行业版和 Dock 模式的负载控制走 services 通道（无 drc_ 前缀），
     * 由 ServiceCommandHandler 直接委托本处理器，无需额外注册。
     */
    @PostConstruct
    public void init() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return;
        }
        DrcProtocol protocol = DrcProtocol.forController(runtimeConfig.getControllerType());
        if (protocol.payloadUsesServiceChannel()) {
            log.info("Pilot 模式（{}）：负载控制走 services 通道，由 ServiceCommandHandler 委托",
                    runtimeConfig.getControllerType());
        } else {
            registerDrcPayloadHandlers(protocol);
        }
    }

    /**
     * 为 DRC 通道注册 drc_ 前缀的负载控制处理器。
     * <p>每个基础方法名通过 {@link DrcProtocol#resolvePayloadMethod} 转换为 drc_ 前缀方法名，
     * 注册到 {@link DrcCommandHandler}，实际逻辑委托给 {@link #handle}。
     */
    private void registerDrcPayloadHandlers(DrcProtocol protocol) {
        for (String baseMethod : PAYLOAD_METHODS) {
            String drcMethod = protocol.resolvePayloadMethod(baseMethod);
            drcCommandHandler.registerHandler(drcMethod, data -> handle(baseMethod, data));
        }
        log.info("Pilot 模式（{}）：已向 DrcCommandHandler 注册 {} 个 drc_ 前缀负载控制处理器",
                runtimeConfig.getControllerType(), PAYLOAD_METHODS.size());
    }

    /**
     * 判断方法是否为负载控制命令（基础方法名，不含 drc_ 前缀）。
     * <p>供 {@link ServiceCommandHandler} 在 routeCommand 中判断是否委托。
     */
    public boolean handles(String method) {
        return PAYLOAD_METHODS.contains(method);
    }

    /**
     * 处理负载控制命令。
     * <p>DJI 文档：负载控制指令的 services_reply/drc_up 均仅有 result，无 output
     * （camera_photo_take 全景模式除外）。
     * camera_mode_switch 额外更新 DeviceState.cameraMode。
     *
     * @param method 基础方法名（不含 drc_ 前缀，如 "camera_aim"）
     * @param data   指令数据
     * @return 回复 output（含 result 字段，可能含 output 字段）
     */
    public Map<String, Object> handle(String method, JsonNode data) {
        // P-10：枚举值校验，非法枚举值返回 result=1
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
                    log.info("全景拍照模式，回复包含 output.status=in_progress");
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
}
