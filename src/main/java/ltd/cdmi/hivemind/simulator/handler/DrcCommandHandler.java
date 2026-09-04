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
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.DrcUpMethod;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.ProtocolValidator;
import ltd.cdmi.hivemind.simulator.mqtt.DrcConnectionManager;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

    /** 拍照完成模拟延迟（秒）：drc_camera_photo_take 后推送 photo_info_push ok 的等待时间 */
    private static final long PHOTO_COMPLETE_DELAY_SECONDS = 2;

    // ==================== DRC 杆量积分运动学常量（TC-DRC-061~065） ====================
    // 简化速度模型：满杆对应最大速度，杆量线性归一化，非精确空气动力学。
    // 量级参考 M4D 实际性能（最大水平速度 21m/s、最大上升速度 8m/s），取偏保守值便于平台轨迹展示调试。
    /** 满杆水平速度（米/秒）：pitch/roll 杆量归一化后的最大速度 */
    private static final double STICK_MAX_HORIZONTAL_SPEED_MPS = 10.0;
    /** 满杆垂直速度（米/秒）：throttle 杆量归一化后的最大速度 */
    private static final double STICK_MAX_VERTICAL_SPEED_MPS = 5.0;
    /** 满杆偏航角速度（度/秒）：yaw 杆量归一化后的最大角速度 */
    private static final double STICK_MAX_YAW_RATE_DEG_S = 60.0;
    /** 积分时间步上限（秒）：stick_control 断流后恢复时防瞬移，超过按上限计算 */
    private static final double STICK_MAX_DT_SECONDS = 0.5;
    /** 杆量中值：stick_control 悬停/无动作基准值（杆量值域 [1024±660]，满杆 1684/364） */
    private static final double STICK_NEUTRAL = 1024.0;
    /** 杆量满杆偏移：中值 1024 到满杆 1684/364 的偏移量 660（依据 DJI SDK 虚拟摇杆最大偏移量 660） */
    private static final double STICK_MAX_OFFSET = 660.0;
    /** 满杆姿态倾角（度）：attitudePitch/Roll 随杆量线性映射的最大倾角 */
    private static final double STICK_MAX_TILT_DEG = 15.0;
    /** 纬度每度对应米数（地球平均半径换算） */
    private static final double METERS_PER_DEGREE_LATITUDE = 111320.0;

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final ObjectMapper objectMapper;
    private final DeviceState state;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final CoverageRecorder coverageRecorder;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;
    private final AiSimulator aiSimulator;
    private final DrcConnectionManager drcConnections;

    /** 按 method 注册的 DRC 指令处理器 */
    private final Map<String, Function<JsonNode, Map<String, Object>>> handlers = new ConcurrentHashMap<>();

    /** 上一条 stick_control 的时间戳（纳秒），0 表示尚未建立积分时间基准 */
    private volatile long lastStickNanos;
    /** M-2 诊断日志只记录一次（杆量符号约定），避免 5-10Hz 高频刷日志 */
    private volatile boolean stickDirectionLogged;

    public DrcCommandHandler(SimulatorProperties props, MqttClientManager mqtt,
                             ObjectMapper objectMapper, DeviceState state,
                             DiagnosticLogRecorder diagnosticRecorder,
                             CoverageRecorder coverageRecorder,
                             RuntimeConfig runtimeConfig,
                             DockTopicSchema dockTopicSchema,
                             AiSimulator aiSimulator,
                             DrcConnectionManager drcConnections) {
        this.props = props;
        this.mqtt = mqtt;
        this.objectMapper = objectMapper;
        this.state = state;
        this.diagnosticRecorder = diagnosticRecorder;
        this.coverageRecorder = coverageRecorder;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
        this.aiSimulator = aiSimulator;
        this.drcConnections = drcConnections;
    }

    @PostConstruct
    public void init() {
        String gatewaySn = runtimeConfig.getGatewaySn();
        String drcDownTopic = dockTopicSchema.topic(dockTopicSchema.drcDown(), gatewaySn);
        // 双订阅去重（TC-DRC-073）：DRC 专用连接激活期间，主连接收到的 drc/down 忽略
        //（专用连接也订阅同一 topic，两份消息只处理专用连接那份，避免杆量指令双份积分）
        mqtt.addListener(drcDownTopic, (topic, payload) -> {
            if (!drcConnections.isActive()) {
                handleDrcCommand(topic, payload);
            }
        });
        // DRC 专用连接的消息处理器（drc_mode_enter 建连成功后生效）
        drcConnections.onMessage(this::handleDrcCommand);
        log.info("DrcCommandHandler 已注册监听: {}", drcDownTopic);

        // 注册 Phase 2 飞行安全指令
        registerSafetyHandlers();

        // 注册 DRC 飞行控制指令（stick_control/drone_control，无回包机制）
        registerFlightControlHandlers();

        // Dock3 专属指令（共 24 个）：相机高级控制（4 个）+ 探照灯（4 个，AL1）+ 喊话器（5 个，AS1）+ AI 识别（11 个）。
        // SDK DrcMethod 类注释明确 Dock3 独有，Dock1/Dock2 不注册，收到时走兜底（result=0 + S-2 诊断日志），
        // 避免掩盖平台对 Dock 版本的协议分类偏差。对齐 TDD-SPEC TC-DRC-DOCK3-001~003。
        if (runtimeConfig.getDockType() == DockModel.DOCK3) {
            // 注册 Phase 3 相机高级控制指令（Dock3 专属 4 个）
            registerCameraAdvancedHandlers();

            // 注册 Phase 4 探照灯控制指令（Dock3 专属 4 个，AL1）
            registerLightHandlers();

            // 注册 Phase 4 喊话器控制指令（Dock3 专属 5 个，AS1）
            registerSpeakerHandlers();

            // 注册 Phase 5 AI 识别控制指令（Dock3 专属 11 个，DJI Cloud API v1.16 新增）
            registerAiHandlers();
        }
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
     * 注册 Phase 5 AI 识别控制指令处理器（Dock3 专属，DJI Cloud API v1.16 新增）。
     * <p>10 个 DRC 下行指令，每个指令处理后 AiSimulator 自动推送 drc_ai_info_push 状态更新。
     * <p>详见 DJI Cloud API <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/dock-feature-set/ai-target-recognition.html">AI 目标识别</a>。
     */
    private void registerAiHandlers() {
        // drc_ai_identify_set：AI 识别开关设置
        registerHandler(AiSimulator.DRC_AI_IDENTIFY_SET, data -> aiSimulator.handleAiIdentifySet(data));

        // drc_ai_model_select：AI 模型选择
        registerHandler(AiSimulator.DRC_AI_MODEL_SELECT, data -> aiSimulator.handleAiModelSelect(data));

        // drc_ai_identify_score_mode_set：设置 AI 识别置信度模式
        registerHandler(AiSimulator.DRC_AI_IDENTIFY_SCORE_MODE_SET, data -> aiSimulator.handleAiIdentifyScoreModeSet(data));

        // drc_ai_identify_score_set：设置 AI 识别置信度
        registerHandler(AiSimulator.DRC_AI_IDENTIFY_SCORE_SET, data -> aiSimulator.handleAiIdentifyScoreSet(data));

        // drc_ai_identify_score_reset：重置 AI 识别置信度
        registerHandler(AiSimulator.DRC_AI_IDENTIFY_SCORE_RESET, data -> aiSimulator.handleAiIdentifyScoreReset(data));

        // drc_ai_identify_filter_set：设置 AI 识别目标过滤列表
        registerHandler(AiSimulator.DRC_AI_IDENTIFY_FILTER_SET, data -> aiSimulator.handleAiIdentifyFilterSet(data));

        // drc_ai_spotlight_zoom_set：AI 跟随开关设置
        registerHandler(AiSimulator.DRC_AI_SPOTLIGHT_ZOOM_SET, data -> aiSimulator.handleAiSpotlightZoomSet(data));

        // drc_ai_spotlight_zoom_track：AI 识别目标跟随（按 target_index）
        registerHandler(AiSimulator.DRC_AI_SPOTLIGHT_ZOOM_TRACK, data -> aiSimulator.handleAiSpotlightZoomTrack(data));

        // drc_ai_spotlight_zoom_select：AI 框选目标跟随
        registerHandler(AiSimulator.DRC_AI_SPOTLIGHT_ZOOM_SELECT, data -> aiSimulator.handleAiSpotlightZoomSelect(data));

        // drc_ai_spotlight_zoom_confirm：AI 框选目标跟随确认
        registerHandler(AiSimulator.DRC_AI_SPOTLIGHT_ZOOM_CONFIRM, data -> aiSimulator.handleAiSpotlightZoomConfirm(data));

        // drc_ai_spotlight_zoom_stop：停止目标跟随
        registerHandler(AiSimulator.DRC_AI_SPOTLIGHT_ZOOM_STOP, data -> aiSimulator.handleAiSpotlightZoomStop(data));
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
        // 杆量积分模拟（TC-DRC-061~065）：按杆量与消息间隔推进经纬度/高度/偏航，OSD 周期上报自然反映
        registerHandler(DrcMethod.STICK_CONTROL.methodName(), data -> {
            int roll = data.path("roll").asInt();
            int pitch = data.path("pitch").asInt();
            int throttle = data.path("throttle").asInt();
            int yaw = data.path("yaw").asInt();
            integrateStick(System.nanoTime(), roll, pitch, throttle, yaw);
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

        // （stick_control 积分逻辑见 integrateStick，此处仅注册路由）

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
            // interval 字段为字符串类型（DJI 文档定义），state 中存为 double 对齐 camera_state_push 示例值 2.5
            // 闭环：指令写入 state.intervalPhotoInterval → buildDrcCameraState 读取同一字段上报
            double intervalValue = interval.isEmpty() ? 2.5 : Double.parseDouble(interval);
            state.setIntervalPhotoInterval(intervalValue);
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

        // drc_camera_screen_drag：画面拖动控制（更新云台角度，影响 osd_info_push）
        registerHandler("drc_" + ServiceMethod.CAMERA_SCREEN_DRAG.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            boolean locked = data.path("locked").asBoolean();
            double pitchSpeed = data.path("pitch_speed").asDouble();
            double yawSpeed = data.path("yaw_speed").asDouble();
            // 画面拖动 → 云台角度按速度增量更新（对齐 TDD-SPEC TC-DRC-GIMBAL-003）
            state.setGimbalPitch(state.getGimbalPitch() + pitchSpeed);
            state.setGimbalYaw(state.getGimbalYaw() + yawSpeed);
            log.info("DRC 画面拖动: payload_index={}, locked={}, pitch_speed={}, yaw_speed={}, gimbal_pitch={}, gimbal_yaw={}",
                    payloadIndex, locked, pitchSpeed, yawSpeed, state.getGimbalPitch(), state.getGimbalYaw());
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

        // drc_gimbal_reset：重置云台（写入 state，影响 osd_info_push）
        registerHandler("drc_" + ServiceMethod.GIMBAL_RESET.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            int resetMode = data.path("reset_mode").asInt();
            // reset_mode: 0=回中(pitch/yaw/roll 归零), 1=向下(pitch=-90), 2=偏航回中(yaw=0), 3=俯仰向下(pitch=-90)
            // 对齐 TDD-SPEC TC-DRC-GIMBAL-001/002
            switch (resetMode) {
                case 0 -> { state.setGimbalPitch(0.0); state.setGimbalYaw(0.0); state.setGimbalRoll(0.0); }
                case 1 -> state.setGimbalPitch(-90.0);
                case 2 -> state.setGimbalYaw(0.0);
                case 3 -> state.setGimbalPitch(-90.0);
            }
            log.info("DRC 重置云台: payload_index={}, reset_mode={} (0=回中,1=向下,2=偏航回中,3=俯仰向下), gimbal_pitch={}, gimbal_yaw={}, gimbal_roll={}",
                    payloadIndex, resetMode, state.getGimbalPitch(), state.getGimbalYaw(), state.getGimbalRoll());
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

        // drc_camera_photo_take：开始拍照（保存到 state，影响 drc_camera_state_push；推送 drc_camera_photo_info_push 进度）
        registerHandler("drc_" + ServiceMethod.CAMERA_PHOTO_TAKE.methodName(), data -> {
            String payloadIndex = data.path("payload_index").asText();
            state.setPhotoState(1);
            log.info("DRC 开始拍照: payload_index={}", payloadIndex);
            publishPhotoInfoPush("in_progress", 0);
            // 拍照完成模拟：2 秒后推送 ok（photoState 已被 photo_stop 归 0 时视为中断，不推送）
            CompletableFuture.delayedExecutor(PHOTO_COMPLETE_DELAY_SECONDS, TimeUnit.SECONDS)
                    .execute(() -> {
                        if (state.getPhotoState() == 1) {
                            state.setPhotoState(0);
                            publishPhotoInfoPush("ok", 100);
                            log.info("DRC 拍照完成: payload_index={}", payloadIndex);
                        }
                    });
            return success();
        });
    }

    /**
     * 推送 drc_camera_photo_info_push 拍照信息事件（变化时推送）。
     * <p>详见 DJI Cloud API Dock3 remote-control「拍照信息推送」：data 含 result/status/progress，
     * 用于全景拍照等持续拍照场景的进度上报。countdown_time 为定时拍照倒计时扩展字段，
     * 模拟器拍照为瞬时模拟不产生倒计时，暂不上报（M-2 诊断日志已记录）。</p>
     */
    private void publishPhotoInfoPush(String status, int percent) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("current_step", 0);
        progress.put("percent", percent);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", 0);
        data.put("status", status);
        data.put("progress", progress);
        publishDrcEvent(DrcUpMethod.DRC_CAMERA_PHOTO_INFO_PUSH.methodName(), data);
    }

    /**
     * stick_control 杆量积分：按杆量与消息时间间隔推进无人机位置/高度/偏航（TC-DRC-061~065）。
     * <p>简化速度模型：满杆对应最大速度（{@link #STICK_MAX_HORIZONTAL_SPEED_MPS} 等），杆量线性归一化。
     * 位移沿机头方向（attitudeYaw）投影到东北坐标系，经纬度按每度 111320 米换算。
     * 首条消息仅建立时间基准（dt=0 不位移）；断流超过 {@link #STICK_MAX_DT_SECONDS} 按 0.5s 封顶防瞬移。
     * 在舱（droneInDock）时忽略杆量，与真实设备一致。
     * <p>姿态角模拟：attitudePitch/Roll 随杆量线性映射倾角（前推杆机头下俯为负、右压杆右倾为正），
     * 不积分（摇杆松开即回平，与真实无人机姿态响应一致）。
     * <p>结果写入 {@link DeviceState}，由 OSD 0.5Hz 周期上报自然反映到 thing/product/{sn}/osd。
     *
     * @param nowNanos 当前时间戳（纳秒），测试可注入确定性时间
     */
    void integrateStick(long nowNanos, int roll, int pitch, int throttle, int yaw) {
        // M-2 诊断日志（一次性）：杆量值域与符号约定均未在 Dock 文档独立明确，按 Pilot 文档+SDK 推定，待真机验证
        if (!stickDirectionLogged && (roll != 0 || pitch != 0 || throttle != 0 || yaw != 0)) {
            stickDirectionLogged = true;
            diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, DrcMethod.STICK_CONTROL.methodName(),
                    "杆量值域[1024±660]（中值1024，满杆1684/364）：Dock端stick_control字段表未在Dock2/Dock3文档独立列出，"
                            + "依据Pilot RC Plus 2文档constraint{max:1684,min:364}+SDK虚拟摇杆最大偏移量660推定，待真机验证。"
                            + "杆量符号约定：pitch正=前飞、roll正=右移、throttle正=上升、yaw正=顺时针旋转，"
                            + "DJI文档未逐项明确stick_control符号语义，按遥控器摇杆习惯实现，待真机验证");
        }

        // 在舱设备不响应杆量
        if (state.isDroneInDock()) {
            return;
        }

        // 计算积分时间步：首条建立基准（dt=0），断流按上限封顶
        double dt = 0.0;
        if (lastStickNanos != 0) {
            dt = Math.min((nowNanos - lastStickNanos) / 1_000_000_000.0, STICK_MAX_DT_SECONDS);
            if (dt < 0) {
                dt = 0.0;  // 时钟回退保护
            }
        }
        lastStickNanos = nowNanos;

        // 满杆归一化 [-1, 1]
        double nPitch = clampStick(pitch);
        double nRoll = clampStick(roll);
        double nThrottle = clampStick(throttle);
        double nYaw = clampStick(yaw);

        // 偏航积分（度，[0, 360) 归一化）
        double heading = Math.floorMod(
                (long) (state.getAttitudeYaw() + STICK_MAX_YAW_RATE_DEG_S * nYaw * dt), 360);
        state.setAttitudeYaw(heading);

        // 姿态倾角（瞬时映射，不积分）
        state.setAttitudePitch(-nPitch * STICK_MAX_TILT_DEG);  // 前推杆机头下俯为负
        state.setAttitudeRoll(nRoll * STICK_MAX_TILT_DEG);     // 右压杆右倾为正

        if (dt > 0) {
            // 机体坐标系速度 → 东北坐标系位移（速度 × dt，heading 0=北，顺时针增大）
            double rad = Math.toRadians(heading);
            double vForward = STICK_MAX_HORIZONTAL_SPEED_MPS * nPitch;
            double vRight = STICK_MAX_HORIZONTAL_SPEED_MPS * nRoll;
            double east = (vForward * Math.sin(rad) + vRight * Math.cos(rad)) * dt;
            double north = (vForward * Math.cos(rad) - vRight * Math.sin(rad)) * dt;

            double latitude = state.getDroneLatitude() + north / METERS_PER_DEGREE_LATITUDE;
            // 经度每度米数随纬度收缩
            double metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(latitude));
            double longitude = state.getDroneLongitude() + east / metersPerDegreeLongitude;

            double dh = STICK_MAX_VERTICAL_SPEED_MPS * nThrottle * dt;

            state.setDroneLatitude(latitude);
            state.setDroneLongitude(longitude);
            state.setDroneHeight(state.getDroneHeight() + dh);
            state.setDroneElevation(state.getDroneElevation() + dh);  // 椭球高 = 机场海拔 + 相对高度
        }

        log.debug("DRC 杆量积分: roll={}, pitch={}, throttle={}, yaw={}, dt={}s, "
                        + "lat={}, lng={}, height={}, heading={}",
                roll, pitch, throttle, yaw, dt,
                state.getDroneLatitude(), state.getDroneLongitude(),
                state.getDroneHeight(), state.getAttitudeYaw());
    }

    /** 杆量归一化到 [-1, 1]：中值 1024 → 0，满杆 1684/364 → ±1（超界截断） */
    private double clampStick(int value) {
        double normalized = (value - STICK_NEUTRAL) / STICK_MAX_OFFSET;
        return Math.max(-1.0, Math.min(1.0, normalized));
    }

    /**
     * 发布 DRC 事件推送消息到 drc/up。
     * <p>格式：{@code {method, data, timestamp, seq}}（区别于命令回复的 {method, data, seq}）</p>
     * <p>发布路由（TC-DRC-071）：DRC 专用连接在线时从专用连接发布（真机行为，EMQX 消息来源为 drc client），
     * 离线时回退主连接。</p>
     */
    private void publishDrcEvent(String method, Map<String, Object> data) {
        Map<String, Object> event = DrcMessage.event(method, data);
        String drcUpTopic = dockTopicSchema.topic(dockTopicSchema.drcUp(), runtimeConfig.getGatewaySn());
        publishDrcUp(drcUpTopic, event);
        log.info("已推送 DRC 事件: method={}, status={}", method, data.get("status"));
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
     * <p>发布路由同 publishDrcEvent（TC-DRC-071）。</p>
     */
    private void publishDrcReply(String method, Map<String, Object> data, int seq) {
        Map<String, Object> reply = DrcMessage.reply(method, data, seq);
        String drcUpTopic = dockTopicSchema.topic(dockTopicSchema.drcUp(), runtimeConfig.getGatewaySn());
        publishDrcUp(drcUpTopic, reply);
        log.info("已回复 DRC: method={}, seq={}, result={}", method, seq,
                data.getOrDefault("result", 0));
    }

    /**
     * drc/up 统一发布入口（TC-DRC-071）：DRC 专用连接在线时从专用连接发布（真机行为），
     * 离线时走主连接 publishJson（v1.4.8 行为）。日志记录由
     * DrcConnectionManager.publishUp / MqttClientManager.publishJson 内部完成。
     */
    private void publishDrcUp(String topic, Map<String, Object> message) {
        if (!drcConnections.isActive()) {
            mqtt.publishJson(topic, message);
            return;
        }
        try {
            // 专用连接在线：从专用连接发布（发布失败时 publishUp 内部回退主连接）
            drcConnections.publishUp(topic, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.error("drc/up 序列化失败 topic={}: {}", topic, e.getMessage(), e);
        }
    }
}
