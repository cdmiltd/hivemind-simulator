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
import ltd.cdmi.hivemind.simulator.mqtt.DrcMessage;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.TopicConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * DRC 远程控制指令路由与应答处理器。
 * <p>订阅 thing/product/{gateway_sn}/drc/down，按 method 路由到对应处理器，统一回 drc/up。
 * <p>DRC 消息格式：{@code {method, data, seq}}（与 OSD 的 {tid, bid, timestamp, data} 不同）。
 * <p>详见 DJI Cloud API <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html">Dock3 远程控制</a>。
 */
@Component
public class DrcCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(DrcCommandHandler.class);

    /** 无人机 mode_code：降落中（DJI 飞行器 mode_code=12） */
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

    /** 按 method 注册的 DRC 指令处理器 */
    private final Map<String, Function<JsonNode, Map<String, Object>>> handlers = new ConcurrentHashMap<>();

    public DrcCommandHandler(SimulatorProperties props, MqttClientManager mqtt,
                             ObjectMapper objectMapper, DeviceState state,
                             DiagnosticLogRecorder diagnosticRecorder,
                             CoverageRecorder coverageRecorder,
                             RuntimeConfig runtimeConfig) {
        this.props = props;
        this.mqtt = mqtt;
        this.objectMapper = objectMapper;
        this.state = state;
        this.diagnosticRecorder = diagnosticRecorder;
        this.coverageRecorder = coverageRecorder;
        this.runtimeConfig = runtimeConfig;
    }

    @PostConstruct
    public void init() {
        String dockSn = runtimeConfig.getDockSn();
        mqtt.addListener(TopicConstants.topic(TopicConstants.DRC_DOWN, dockSn), this::handleDrcCommand);
        log.info("DrcCommandHandler 已注册监听: {}", TopicConstants.topic(TopicConstants.DRC_DOWN, dockSn));

        // 注册 Phase 2 飞行安全指令
        registerSafetyHandlers();

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
     */
    private void registerSafetyHandlers() {
        // drc_force_landing：强制降落（无视障碍物直接降落）
        registerHandler("drc_force_landing", data -> {
            state.setDroneModeCode(DRONE_MODE_LANDING);
            log.info("DRC 强制降落：飞行器进入降落状态");
            return success();
        });

        // drone_emergency_stop：急停（取消降落/飞行，电机停止）
        registerHandler("drone_emergency_stop", data -> {
            state.setDroneModeCode(DRONE_MODE_STANDBY);
            log.info("DRC 急停：飞行器停止");
            return success();
        });

        // drc_emergency_landing：紧急降落（受避障影响可能中止）
        registerHandler("drc_emergency_landing", data -> {
            state.setDroneModeCode(DRONE_MODE_LANDING);
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
        registerHandler("drc_camera_night_mode_set", data -> {
            int mode = data.path("mode").asInt();
            state.setNightMode(mode);
            log.info("DRC 夜景模式设置: mode={} (0=关闭,1=开启,2=自动)", mode);
            return success();
        });

        // drc_camera_denoise_level_set：降噪等级设置
        registerHandler("drc_camera_denoise_level_set", data -> {
            int level = data.path("level").asInt();
            state.setDenoiseLevel(level);
            log.info("DRC 降噪等级设置: level={} (2=增强15fps,3=超强5fps)", level);
            return success();
        });

        // drc_camera_night_vision_enable：黑白夜视使能
        registerHandler("drc_camera_night_vision_enable", data -> {
            boolean enable = data.path("enable").asBoolean();
            state.setNightVisionEnable(enable);
            log.info("DRC 黑白夜视: enable={}", enable);
            return success();
        });

        // drc_infrared_fill_light_enable：近红外补光使能
        registerHandler("drc_infrared_fill_light_enable", data -> {
            boolean enable = data.path("enable").asBoolean();
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
        registerHandler("drc_light_brightness_set", data -> {
            int brightness = data.path("brightness").asInt();
            state.setLightBrightness(brightness);
            log.info("DRC 探照灯亮度: brightness={}", brightness);
            return success();
        });

        // drc_light_mode_set：探照灯模式设置
        registerHandler("drc_light_mode_set", data -> {
            int mode = data.path("mode").asInt();
            state.setLightMode(mode);
            log.info("DRC 探照灯模式: mode={} (0=关闭,1=常亮,2=爆闪,3=快速爆闪,4=交替爆闪)", mode);
            return success();
        });

        // drc_light_fine_tuning_set：探照灯左右角度微调
        registerHandler("drc_light_fine_tuning_set", data -> {
            int position = data.path("position").asInt();  // 0=左灯, 1=右灯
            int value = data.path("value").asInt();
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
        registerHandler("drc_light_calibration", data -> {
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
        registerHandler("drc_speaker_play_mode_set", data -> {
            int playMode = data.path("play_mode").asInt();
            state.setSpeakerPlayMode(playMode);
            log.info("DRC 喊话器播放模式: play_mode={} (0=单次,1=循环)", playMode);
            return success();
        });

        // drc_speaker_tts_set：喊话器TTS喊话设置
        registerHandler("drc_speaker_tts_set", data -> {
            int volume = data.path("volume").asInt();
            int type = data.path("type").asInt();
            int language = data.path("language").asInt();
            int speed = data.path("speed").asInt();
            state.setSpeakerVolume(volume);
            state.setSpeakerPlaying(true);
            log.info("DRC 喊话器TTS: volume={}, type={} (0=男,1=女), language={} (0=中,1=英), speed={}",
                    volume, type, language, speed);
            return success();
        });

        // drc_speaker_play_volume_set：喊话器音量设置
        registerHandler("drc_speaker_play_volume_set", data -> {
            int volume = data.path("play_volume").asInt();
            state.setSpeakerVolume(volume);
            log.info("DRC 喊话器音量: play_volume={}", volume);
            return success();
        });

        // drc_speaker_play_stop：喊话器停止播放
        registerHandler("drc_speaker_play_stop", data -> {
            state.setSpeakerPlaying(false);
            log.info("DRC 喊话器停止播放");
            return success();
        });

        // drc_speaker_replay：喊话器重新播放
        registerHandler("drc_speaker_replay", data -> {
            state.setSpeakerPlaying(true);
            log.info("DRC 喊话器重新播放");
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

            publishDrcReply(method, replyData, seq);
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
        String drcUpTopic = TopicConstants.topic(TopicConstants.DRC_UP, runtimeConfig.getDockSn());
        mqtt.publishJson(drcUpTopic, reply);
        log.info("已回复 DRC: method={}, seq={}, result={}", method, seq,
                data.getOrDefault("result", 0));
    }
}
