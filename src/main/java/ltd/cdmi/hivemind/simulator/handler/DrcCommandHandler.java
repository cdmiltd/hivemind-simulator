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
import ltd.cdmi.dji.cloudapi.sdk.codec.MessageCodec;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.camera.DrcCameraDenoiseLevelSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.camera.DrcCameraNightModeSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.camera.DrcCameraNightVisionEnableRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.camera.DrcInfraredFillLightEnableRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.light.DrcLightBrightnessSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.light.DrcLightFineTuningSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.light.DrcLightModeSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.speaker.DrcSpeakerPlayModeSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.speaker.DrcSpeakerPlayVolumeSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.drc.speaker.DrcSpeakerTtsSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.DrcMethod;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.ServiceMethod;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.ProtocolValidator;
import ltd.cdmi.hivemind.simulator.mqtt.DrcMessage;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * DRC 远程控制指令路由与应答处理器。
 * <p>订阅 thing/product/{gateway_sn}/drc/down，按 method 路由到对应处理器，统一回 drc/up。
 * <p>DRC 消息格式：{@code {method, data, seq}}（与 OSD 的 {tid, bid, timestamp, data} 不同）。
 * <p>详见 DJI Cloud API <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/remote-control.html">Dock2 远程控制</a>。
 */
@Component
public class DrcCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(DrcCommandHandler.class);

    /**
     * 无人机 mode_code：降落中。
     * <p>待确认：DJI 文档中 10=自动降落、11=强制降落、12=三桨叶降落。
     * drc_force_landing（强制降落）可能应对应 11=强制降落，
     * drc_emergency_landing（紧急降落）可能应对应 10=自动降落，
     * 当前统一使用 12，文档未明确各 DRC 降落指令对应的 mode_code，待真机验证。
     */
    private static final int DRONE_MODE_LANDING = 12;
    /** 无人机 mode_code：待机（DJI 飞行器 mode_code=0） */
    private static final int DRONE_MODE_STANDBY = 0;

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final ObjectMapper objectMapper;
    private final DeviceState state;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final CoverageRecorder coverageRecorder;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    /** 按 method 注册的 DRC 指令处理器 */
    private final Map<String, Function<JsonNode, Map<String, Object>>> handlers = new ConcurrentHashMap<>();

    public DrcCommandHandler(SimulatorProperties props, MqttClientManager mqtt,
                             ObjectMapper objectMapper, DeviceState state,
                             DiagnosticLogRecorder diagnosticRecorder,
                             CoverageRecorder coverageRecorder,
                             RuntimeConfig runtimeConfig,
                             DockTopicSchema dockTopicSchema) {
        this.props = props;
        this.mqtt = mqtt;
        this.objectMapper = objectMapper;
        this.state = state;
        this.diagnosticRecorder = diagnosticRecorder;
        this.coverageRecorder = coverageRecorder;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
    }

    @PostConstruct
    public void init() {
        String gatewaySn = runtimeConfig.getGatewaySn();
        mqtt.addListener(dockTopicSchema.topic(dockTopicSchema.drcDown(), gatewaySn), this::handleDrcCommand);
        log.info("DrcCommandHandler 已注册监听: {}", dockTopicSchema.topic(dockTopicSchema.drcDown(), gatewaySn));

        // 注册 Phase 2 飞行安全指令
        registerSafetyHandlers();

        // 注册 DRC 飞行控制指令（stick_control/drone_control，无回包机制）
        registerFlightControlHandlers();

        // 注册 Phase 3 相机高级控制指令
        registerCameraAdvancedHandlers();

        // 注册 Phase 4 探照灯控制指令
        registerLightHandlers();

        // 注册 Phase 4 喊话器控制指令
        registerSpeakerHandlers();
    }

    /**
     * 注册 DRC 指令处理器。
     * @param method DRC 方法名
     * @param handler 接收 data，返回回复 data（含 result 字段）
     */
    public void registerHandler(String method, Function<JsonNode, Map<String, Object>> handler) {
        handlers.put(method, handler);
        log.info("已注册 DRC 指令处理器: {}", method);
    }

    /**
     * 注册 Phase 2 飞行安全指令处理器。
     * <p>三个指令格式一致：请求 data 为空，回复 data 为 {result:0}。</p>
     * <p>drc_force_landing/drc_emergency_landing 文档来源：Dock2 remote-control.html，
     * Dock3 文档（drc.html + remote-control.html）中未找到，Dock1 remote-control.html 无法访问待确认。
     * 模拟器对所有 Dock 类型均处理，Dock3 收到时记录 M-2 诊断待真机验证。</p>
     */
    private void registerSafetyHandlers() {
        // drc_force_landing：强制降落（无视障碍物直接降落）
        // 文档来源：Dock2 remote-control.html#强制降落
        registerHandler(DrcMethod.DRC_FORCE_LANDING.methodName(), data -> {
            state.setDroneModeCode(DRONE_MODE_LANDING);
            // M-2：mode_code=12(三桨叶降落)待确认，DJI文档中 11=强制降落，待真机验证
            diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, DrcMethod.DRC_FORCE_LANDING.methodName(),
                    "mode_code=12(三桨叶降落)，DJI文档中 11=强制降落，文档未明确 DRC 强制降落对应的 mode_code，待真机验证");
            // M-2：Dock3 文档中未找到 drc_force_landing，待真机验证
            if (runtimeConfig.getDockType() == DockModel.DOCK3) {
                diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, DrcMethod.DRC_FORCE_LANDING.methodName(),
                        "Dock3 文档（drc.html + remote-control.html）中未找到此方法，待真机验证");
            }
            log.info("DRC 强制降落：飞行器进入降落状态");
            return success();
        });

        // drone_emergency_stop：急停（取消降落/飞行，电机停止）
        registerHandler(DrcMethod.DRONE_EMERGENCY_STOP.methodName(), data -> {
            state.setDroneModeCode(DRONE_MODE_STANDBY);
            log.info("DRC 急停：飞行器停止");
            return success();
        });

        // drc_emergency_landing：紧急降落（受避障影响可能中止）
        // 文档来源：Dock2 remote-control.html#紧急降落
        registerHandler(DrcMethod.DRC_EMERGENCY_LANDING.methodName(), data -> {
            state.setDroneModeCode(DRONE_MODE_LANDING);
            // M-2：mode_code=12(三桨叶降落)待确认，DJI文档中 10=自动降落，待真机验证
            diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, DrcMethod.DRC_EMERGENCY_LANDING.methodName(),
                    "mode_code=12(三桨叶降落)，DJI文档中 10=自动降落，文档未明确 DRC 紧急降落对应的 mode_code，待真机验证");
            // M-2：Dock3 文档中未找到 drc_emergency_landing，待真机验证
            if (runtimeConfig.getDockType() == DockModel.DOCK3) {
                diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, DrcMethod.DRC_EMERGENCY_LANDING.methodName(),
                        "Dock3 文档（drc.html + remote-control.html）中未找到此方法，待真机验证");
            }
            log.info("DRC 紧急降落：飞行器进入降落状态");
            return success();
        });
    }

    /**
     * 注册 Phase 3 相机高级控制指令处理器（Dock3 专属）。
     * <p>四个指令均携带 payload_index，回复 {result:0}。</p>
     */
    private void registerCameraAdvancedHandlers() {
        // drc_camera_night_mode_set：夜景模式设置
        registerHandler(DrcMethod.DRC_CAMERA_NIGHT_MODE_SET.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcCameraNightModeSetRequest.class);
            int mode = req.mode();
            state.setNightMode(mode);
            log.info("DRC 夜景模式设置: mode={} (0=关闭,1=开启,2=自动)", mode);
            return success();
        });

        // drc_camera_denoise_level_set：降噪等级设置
        registerHandler(DrcMethod.DRC_CAMERA_DENOISE_LEVEL_SET.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcCameraDenoiseLevelSetRequest.class);
            int level = req.level();
            state.setDenoiseLevel(level);
            log.info("DRC 降噪等级设置: level={} (2=增强15fps,3=超强5fps)", level);
            return success();
        });

        // drc_camera_night_vision_enable：黑白夜视使能
        registerHandler(DrcMethod.DRC_CAMERA_NIGHT_VISION_ENABLE.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcCameraNightVisionEnableRequest.class);
            boolean enable = req.enable();
            state.setNightVisionEnable(enable);
            log.info("DRC 黑白夜视: enable={}", enable);
            return success();
        });

        // drc_infrared_fill_light_enable：近红外补光使能
        registerHandler(DrcMethod.DRC_INFRARED_FILL_LIGHT_ENABLE.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcInfraredFillLightEnableRequest.class);
            boolean enable = req.enable();
            state.setInfraredFillLightEnable(enable);
            log.info("DRC 近红外补光: enable={}", enable);
            return success();
        });
    }

    /**
     * 注册 Phase 4 探照灯控制指令处理器（Dock3 专属）。
     * <p>四个指令均携带 psdk_index，回复 {result:0}。</p>
     */
    private void registerLightHandlers() {
        // drc_light_brightness_set：探照灯亮度设置
        registerHandler(DrcMethod.DRC_LIGHT_BRIGHTNESS_SET.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcLightBrightnessSetRequest.class);
            int brightness = req.brightness();
            state.setLightBrightness(brightness);
            log.info("DRC 探照灯亮度: brightness={}", brightness);
            return success();
        });

        // drc_light_mode_set：探照灯模式设置
        registerHandler(DrcMethod.DRC_LIGHT_MODE_SET.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcLightModeSetRequest.class);
            int mode = req.mode();
            state.setLightMode(mode);
            log.info("DRC 探照灯模式: mode={} (0=关闭,1=常亮,2=爆闪,3=快速爆闪,4=交替爆闪)", mode);
            return success();
        });

        // drc_light_fine_tuning_set：探照灯左右角度微调
        registerHandler(DrcMethod.DRC_LIGHT_FINE_TUNING_SET.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcLightFineTuningSetRequest.class);
            int position = req.position();  // 0=左灯, 1=右灯
            int value = req.value();
            if (position == 0) {
                state.setLightLeftAngle(value);
                log.info("DRC 探照灯左灯角度: value={}", value);
            } else {
                state.setLightRightAngle(value);
                log.info("DRC 探照灯右灯角度: value={}", value);
            }
            return success();
        });

        // drc_light_calibration：探照灯云台校准
        registerHandler(DrcMethod.DRC_LIGHT_CALIBRATION.methodName(), data -> {
            log.info("DRC 探照灯云台校准执行");
            return success();
        });
    }

    /**
     * 注册 Phase 4 喊话器控制指令处理器（Dock3 专属）。
     * <p>五个指令均携带 psdk_index，回复 {result:0}。</p>
     */
    private void registerSpeakerHandlers() {
        // drc_speaker_play_mode_set：喊话器播放模式设置
        registerHandler(DrcMethod.DRC_SPEAKER_PLAY_MODE_SET.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcSpeakerPlayModeSetRequest.class);
            int playMode = req.playMode();
            state.setSpeakerPlayMode(playMode);
            log.info("DRC 喊话器播放模式: play_mode={} (0=单次,1=循环)", playMode);
            return success();
        });

        // drc_speaker_tts_set：喊话器TTS喊话设置
        registerHandler(DrcMethod.DRC_SPEAKER_TTS_SET.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcSpeakerTtsSetRequest.class);
            int volume = req.volume();
            int type = req.type();
            int language = req.language();
            int speed = req.speed();
            state.setSpeakerVolume(volume);
            state.setSpeakerPlaying(true);
            log.info("DRC 喊话器TTS: volume={}, type={} (0=男,1=女), language={} (0=中,1=英), speed={}",
                    volume, type, language, speed);
            return success();
        });

        // drc_speaker_play_volume_set：喊话器音量设置
        registerHandler(DrcMethod.DRC_SPEAKER_PLAY_VOLUME_SET.methodName(), data -> {
            var req = MessageCodec.fromJson(data.toString(), DrcSpeakerPlayVolumeSetRequest.class);
            int volume = req.playVolume();
            state.setSpeakerVolume(volume);
            log.info("DRC 喊话器音量: play_volume={}", volume);
            return success();
        });

        // drc_speaker_play_stop：喊话器停止播放
        registerHandler(DrcMethod.DRC_SPEAKER_PLAY_STOP.methodName(), data -> {
            state.setSpeakerPlaying(false);
            log.info("DRC 喊话器停止播放");
            return success();
        });

        // drc_speaker_replay：喊话器重新播放
        registerHandler(DrcMethod.DRC_SPEAKER_REPLAY.methodName(), data -> {
            state.setSpeakerPlaying(true);
            log.info("DRC 喊话器重新播放");
            return success();
        });
    }

    /**
     * 注册 DRC 飞行控制指令处理器。
     * <p>DJI 文档明确：
     * <ul>
     *   <li>stick_control（杆量控制）：无回包机制，发送频率需保持 5-10hz</li>
     *   <li>drone_control（飞行控制，已废弃）：成功不回包，仅异常时回包 result=非0</li>
     * </ul>
     * 模拟器默认模拟成功，两个指令均不回包（返回 null）。</p>
     */
    private void registerFlightControlHandlers() {
        // stick_control：DRC-杆量控制（无回包机制）
        registerHandler(DrcMethod.STICK_CONTROL.methodName(), data -> {
            int roll = data.path("roll").asInt();
            int pitch = data.path("pitch").asInt();
            int throttle = data.path("throttle").asInt();
            int yaw = data.path("yaw").asInt();
            log.info("DRC 杆量控制: roll={}, pitch={}, throttle={}, yaw={}", roll, pitch, throttle, yaw);
            return null;  // 无回包机制
        });

        // drone_control：DRC-飞行控制（Dock1 有效，Dock2/Dock3 已废弃）
        registerHandler(DrcMethod.DRONE_CONTROL.methodName(), data -> {
            if (runtimeConfig.getDockType() == DockModel.DOCK1) {
                int seq = data.path("seq").asInt();
                double x = data.path("x").asDouble();
                double y = data.path("y").asDouble();
                double h = data.path("h").asDouble();
                double w = data.path("w").asDouble();
                log.info("DRC 飞行控制: seq={}, x={}, y={}, h={}, w={}", seq, x, y, h, w);
                return null;  // 成功不回包
            }
            log.warn("[P-9] 平台调用了废弃接口 drone_control（DRC-飞行控制），建议使用 stick_control 替代");
            diagnosticRecorder.record(DiagnosticCode.PLATFORM_DEPRECATED_API_CALLED, DrcMethod.DRONE_CONTROL.methodName(),
                    "平台调用了废弃接口 drone_control，DJI 建议使用 stick_control 替代");
            return null;  // 废弃接口，不回包
        });

        // heart_beat：DRC-心跳（回包回显 timestamp，seq 由 DrcMessage.reply 在顶层处理）
        registerHandler(DrcMethod.HEART_BEAT.methodName(), data -> {
            long timestamp = data.path("timestamp").asLong();
            log.info("DRC 心跳: timestamp={}", timestamp);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timestamp", timestamp);
            return result;
        });

        // drc_initial_state_subscribe：DRC初始状态订阅（data=null，回 result=0）
        registerHandler(DrcMethod.DRC_INITIAL_STATE_SUBSCRIBE.methodName(), data -> {
            log.info("DRC 初始状态订阅");
            return success();
        });

        // drc_camera_dewarping_set：镜头去畸变设置
        registerHandler(DrcMethod.DRC_CAMERA_DEWARPING_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            int dewarpingState = data.path("dewarping_state").asInt();
            log.info("DRC 镜头去畸变: payload_index={}, camera_type={}, dewarping_state={}",
                    payloadIndex, cameraType, dewarpingState);
            return success();
        });

        // drc_camera_mechanical_shutter_set：机械快门设置
        registerHandler(DrcMethod.DRC_CAMERA_MECHANICAL_SHUTTER_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            int shutterState = data.path("mechanical_shutter_state").asInt();
            log.info("DRC 机械快门: payload_index={}, camera_type={}, mechanical_shutter_state={}",
                    payloadIndex, cameraType, shutterState);
            return success();
        });

        // drc_camera_iso_set：ISO设置
        registerHandler(DrcMethod.DRC_CAMERA_ISO_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            int isoValue = data.path("iso_value").asInt();
            log.info("DRC ISO设置: payload_index={}, camera_type={}, iso_value={}",
                    payloadIndex, cameraType, isoValue);
            return success();
        });

        // drc_camera_shutter_set：相机快门设置
        registerHandler(DrcMethod.DRC_CAMERA_SHUTTER_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            int shutterValue = data.path("shutter_value").asInt();
            log.info("DRC 相机快门: payload_index={}, camera_type={}, shutter_value={}",
                    payloadIndex, cameraType, shutterValue);
            return success();
        });

        // drc_camera_aperture_value_set：相机光圈设置
        registerHandler(DrcMethod.DRC_CAMERA_APERTURE_VALUE_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            int apertureValue = data.path("aperture_value").asInt();
            log.info("DRC 相机光圈: payload_index={}, camera_type={}, aperture_value={}",
                    payloadIndex, cameraType, apertureValue);
            return success();
        });

        // drc_stealth_state_set：隐蔽模式设置（保存到 state，影响 drc_drone_state_push）
        registerHandler(DrcMethod.DRC_STEALTH_STATE_SET.methodName(), data -> {
            int stealthState = data.path("stealth_state").asInt();
            state.setStealthState(stealthState == 1);
            log.info("DRC 隐蔽模式: stealth_state={} (0=关闭,1=开启)", stealthState);
            return success();
        });

        // drc_night_lights_state_set：夜航灯设置（保存到 state，影响 drc_drone_state_push）
        registerHandler(DrcMethod.DRC_NIGHT_LIGHTS_STATE_SET.methodName(), data -> {
            int nightLightsState = data.path("night_lights_state").asInt();
            state.setNightLightsState(nightLightsState == 1);
            log.info("DRC 夜航灯: night_lights_state={} (0=关闭,1=开启)", nightLightsState);
            return success();
        });

        // drc_interval_photo_set：定时拍照间隔设置
        registerHandler(DrcMethod.DRC_INTERVAL_PHOTO_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String interval = data.path("interval").asText();
            log.info("DRC 定时拍照: payload_index={}, interval={}s", payloadIndex, interval);
            return success();
        });

        // drc_photo_storage_set：照片存储设置
        registerHandler("drc_" + ServiceMethod.PHOTO_STORAGE_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            List<String> settings = new ArrayList<>();
            data.path("photo_storage_settings").forEach(node -> settings.add(node.asText()));
            log.info("DRC 照片存储: payload_index={}, photo_storage_settings={}", payloadIndex, settings);
            return success();
        });

        // drc_video_storage_set：视频存储设置
        registerHandler("drc_" + ServiceMethod.VIDEO_STORAGE_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            List<String> settings = new ArrayList<>();
            data.path("video_storage_settings").forEach(node -> settings.add(node.asText()));
            log.info("DRC 视频存储: payload_index={}, video_storage_settings={}", payloadIndex, settings);
            return success();
        });

        // drc_video_resolution_set：视频分辨率设置
        registerHandler(DrcMethod.DRC_VIDEO_RESOLUTION_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String resolution = data.path("video_resolution").asText();
            log.info("DRC 视频分辨率: payload_index={}, video_resolution={}", payloadIndex, resolution);
            return success();
        });

        // drc_linkage_zoom_set：红外联动变焦（仅 M3TD）
        registerHandler(DrcMethod.DRC_LINKAGE_ZOOM_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            boolean linkageState = data.path("state").asBoolean();
            log.info("DRC 红外联动变焦: payload_index={}, state={}", payloadIndex, linkageState);
            return success();
        });

        // drc_camera_mode_switch：切换相机模式（保存到 state，影响 drc_camera_state_push）
        registerHandler("drc_" + ServiceMethod.CAMERA_MODE_SWITCH.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            int cameraMode = data.path("camera_mode").asInt();
            state.setCameraMode(cameraMode);
            log.info("DRC 切换相机模式: payload_index={}, camera_mode={} (0=拍照,1=录像,2=智能低光,3=全景,4=定时拍)",
                    payloadIndex, cameraMode);
            return success();
        });

        // drc_camera_recording_start：开始录像（保存到 state，影响 drc_camera_state_push）
        registerHandler("drc_" + ServiceMethod.CAMERA_RECORDING_START.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            state.setRecordingState(1);
            log.info("DRC 开始录像: payload_index={}", payloadIndex);
            return success();
        });

        // drc_camera_recording_stop：停止录像（保存到 state，影响 drc_camera_state_push）
        registerHandler("drc_" + ServiceMethod.CAMERA_RECORDING_STOP.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            state.setRecordingState(0);
            log.info("DRC 停止录像: payload_index={}", payloadIndex);
            return success();
        });

        // drc_camera_screen_drag：画面拖动控制
        registerHandler("drc_" + ServiceMethod.CAMERA_SCREEN_DRAG.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            boolean locked = data.path("locked").asBoolean();
            double pitchSpeed = data.path("pitch_speed").asDouble();
            double yawSpeed = data.path("yaw_speed").asDouble();
            log.info("DRC 画面拖动: payload_index={}, locked={}, pitch_speed={}, yaw_speed={}",
                    payloadIndex, locked, pitchSpeed, yawSpeed);
            return success();
        });

        // drc_camera_aim：双击成为 AIM
        registerHandler("drc_" + ServiceMethod.CAMERA_AIM.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            boolean locked = data.path("locked").asBoolean();
            double x = data.path("x").asDouble();
            double y = data.path("y").asDouble();
            log.info("DRC 双击AIM: payload_index={}, camera_type={}, locked={}, x={}, y={}",
                    payloadIndex, cameraType, locked, x, y);
            return success();
        });

        // drc_camera_focal_length_set：变焦
        registerHandler("drc_" + ServiceMethod.CAMERA_FOCAL_LENGTH_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            double zoomFactor = data.path("zoom_factor").asDouble();
            log.info("DRC 变焦: payload_index={}, camera_type={}, zoom_factor={}",
                    payloadIndex, cameraType, zoomFactor);
            return success();
        });

        // drc_gimbal_reset：重置云台
        registerHandler("drc_" + ServiceMethod.GIMBAL_RESET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            int resetMode = data.path("reset_mode").asInt();
            log.info("DRC 重置云台: payload_index={}, reset_mode={} (0=回中,1=向下,2=偏航回中,3=俯仰向下)",
                    payloadIndex, resetMode);
            return success();
        });

        // drc_camera_look_at：Look At（飞行器转向目标点）
        registerHandler("drc_" + ServiceMethod.CAMERA_LOOK_AT.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            boolean locked = data.path("locked").asBoolean();
            double latitude = data.path("latitude").asDouble();
            double longitude = data.path("longitude").asDouble();
            double height = data.path("height").asDouble();
            log.info("DRC Look At: payload_index={}, locked={}, lat={}, lng={}, height={}",
                    payloadIndex, locked, latitude, longitude, height);
            return success();
        });

        // drc_camera_screen_split：分屏
        registerHandler("drc_" + ServiceMethod.CAMERA_SCREEN_SPLIT.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            boolean enable = data.path("enable").asBoolean();
            log.info("DRC 分屏: payload_index={}, enable={}", payloadIndex, enable);
            return success();
        });

        // drc_camera_frame_zoom：框选变焦
        registerHandler("drc_" + ServiceMethod.CAMERA_FRAME_ZOOM.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            boolean locked = data.path("locked").asBoolean();
            double x = data.path("x").asDouble();
            double y = data.path("y").asDouble();
            double width = data.path("width").asDouble();
            double height = data.path("height").asDouble();
            log.info("DRC 框选变焦: payload_index={}, camera_type={}, locked={}, x={}, y={}, width={}, height={}",
                    payloadIndex, cameraType, locked, x, y, width, height);
            return success();
        });

        // drc_ir_metering_area_set：红外测温区域设置
        registerHandler("drc_" + ServiceMethod.IR_METERING_AREA_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            double x = data.path("x").asDouble();
            double y = data.path("y").asDouble();
            double width = data.path("width").asDouble();
            double height = data.path("height").asDouble();
            log.info("DRC 红外测温区域: payload_index={}, x={}, y={}, width={}, height={}",
                    payloadIndex, x, y, width, height);
            return success();
        });

        // drc_ir_metering_point_set：红外测温点设置
        registerHandler("drc_" + ServiceMethod.IR_METERING_POINT_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            double x = data.path("x").asDouble();
            double y = data.path("y").asDouble();
            log.info("DRC 红外测温点: payload_index={}, x={}, y={}", payloadIndex, x, y);
            return success();
        });

        // drc_ir_metering_mode_set：红外测温模式设置
        registerHandler("drc_" + ServiceMethod.IR_METERING_MODE_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            int mode = data.path("mode").asInt();
            log.info("DRC 红外测温模式: payload_index={}, mode={} (0=关闭,1=点测温,2=区域测温)",
                    payloadIndex, mode);
            return success();
        });

        // drc_camera_point_focus_action：点对焦
        registerHandler("drc_" + ServiceMethod.CAMERA_POINT_FOCUS_ACTION.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            double x = data.path("x").asDouble();
            double y = data.path("y").asDouble();
            log.info("DRC 点对焦: payload_index={}, camera_type={}, x={}, y={}",
                    payloadIndex, cameraType, x, y);
            return success();
        });

        // drc_camera_focus_value_set：相机对焦值设置
        registerHandler("drc_" + ServiceMethod.CAMERA_FOCUS_VALUE_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            int focusValue = data.path("focus_value").asInt();
            log.info("DRC 对焦值: payload_index={}, camera_type={}, focus_value={}",
                    payloadIndex, cameraType, focusValue);
            return success();
        });

        // drc_camera_focus_mode_set：相机对焦模式设置
        registerHandler("drc_" + ServiceMethod.CAMERA_FOCUS_MODE_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            int focusMode = data.path("focus_mode").asInt();
            log.info("DRC 对焦模式: payload_index={}, camera_type={}, focus_mode={} (0=MF,1=AFS,2=AFC)",
                    payloadIndex, cameraType, focusMode);
            return success();
        });

        // drc_camera_exposure_set：相机曝光值调节
        registerHandler("drc_" + ServiceMethod.CAMERA_EXPOSURE_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            String exposureValue = data.path("exposure_value").asText();
            log.info("DRC 曝光值: payload_index={}, camera_type={}, exposure_value={}",
                    payloadIndex, cameraType, exposureValue);
            return success();
        });

        // drc_camera_exposure_mode_set：相机曝光模式设置
        registerHandler("drc_" + ServiceMethod.CAMERA_EXPOSURE_MODE_SET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            String cameraType = data.path("camera_type").asText();
            int exposureMode = data.path("exposure_mode").asInt();
            log.info("DRC 曝光模式: payload_index={}, camera_type={}, exposure_mode={} (1=自动,2=快门优先,3=光圈优先,4=手动)",
                    payloadIndex, cameraType, exposureMode);
            return success();
        });

        // drc_camera_photo_stop：停止拍照（保存到 state，影响 drc_camera_state_push）
        registerHandler("drc_" + ServiceMethod.CAMERA_PHOTO_STOP.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            state.setPhotoState(0);
            log.info("DRC 停止拍照: payload_index={}", payloadIndex);
            return success();
        });

        // drc_camera_photo_take：开始拍照（保存到 state，影响 drc_camera_state_push）
        registerHandler("drc_" + ServiceMethod.CAMERA_PHOTO_TAKE.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            state.setPhotoState(1);
            log.info("DRC 开始拍照: payload_index={}", payloadIndex);
            return success();
        });
    }

    /** 构造成功回复 */
    private Map<String, Object> success() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", 0);
        return result;
    }

    private void handleDrcCommand(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String method = node.path("method").asText();
            int seq = node.path("seq").asInt();
            JsonNode data = node.path("data");

            // P-6/P-7：主动校验 DRC 必填字段和字段类型（不阻塞流程，仅记录诊断）
            DiagnosticCode fieldError = ProtocolValidator.validateDrcFields(node);
            if (fieldError != null) {
                log.error("{} drc/down 消息字段校验失败: method={}", ProtocolValidator.logPrefix(fieldError), method);
                diagnosticRecorder.record(fieldError, method, "drc/down 消息字段校验失败");
            }

            log.info("收到 DRC 指令: method={}, seq={}", method, seq);

            // 覆盖率统计：记录平台下发的 DRC method（按当前 MQTT 地址归档）
            coverageRecorder.record(runtimeConfig.getMqttHost() + ":" + runtimeConfig.getMqttPort(), method);

            Map<String, Object> replyData;
            Function<JsonNode, Map<String, Object>> handler = handlers.get(method);
            if (handler != null) {
                try {
                    replyData = handler.apply(data);
                } catch (Exception e) {
                    DiagnosticCode code = ProtocolValidator.classifyException(e);
                    log.error("{} DRC 指令处理异常 method={}: {}", ProtocolValidator.logPrefix(code), method, e.getMessage(), e);
                    diagnosticRecorder.record(code, method, "DRC 指令处理异常: " + e.getMessage());
                    replyData = Map.of("result", 1);
                }
            } else {
                log.warn("[S-2] DRC 指令未覆盖，返回占位 result=0: method={}", method);
                diagnosticRecorder.record(DiagnosticCode.SIMULATOR_METHOD_NOT_IMPLEMENTED, method, "DRC 指令未覆盖");
                replyData = Map.of("result", 0);
            }

            // replyData=null 表示该指令无回包机制（如 stick_control/drone_control 成功时不回包）
            if (replyData != null) {
                publishDrcReply(method, replyData, seq);
            }
        } catch (Exception e) {
            DiagnosticCode code = ProtocolValidator.classifyException(e);
            log.error("{} 处理 DRC 消息失败: {}", ProtocolValidator.logPrefix(code), e.getMessage(), e);
            diagnosticRecorder.record(code, "-", "处理 DRC 消息失败: " + e.getMessage());
        }
    }

    /**
     * 发布 DRC 命令回复到 drc/up。
     * <p>格式：{@code {method, data:{result:0,...}, seq}}（seq 与命令一致）</p>
     */
    private void publishDrcReply(String method, Map<String, Object> data, int seq) {
        Map<String, Object> reply = DrcMessage.reply(method, data, seq);
        String drcUpTopic = dockTopicSchema.topic(dockTopicSchema.drcUp(), runtimeConfig.getGatewaySn());
        mqtt.publishJson(drcUpTopic, reply);
        log.info("已回复 DRC: method={}, seq={}, result={}", method, seq,
                data.getOrDefault("result", 0));
    }
}
