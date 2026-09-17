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

package ltd.cdmi.hivemind.simulator.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.DrcUpMethod;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.osd.DockOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.DroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.OsdContext;
import ltd.cdmi.hivemind.simulator.device.osd.RcOsdBuilder;
import ltd.cdmi.hivemind.simulator.handler.AiSimulator;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.DrcMessage;
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
import java.util.concurrent.TimeUnit;

/**
 * 设备模拟器：0.5Hz 构造并发布 Dock + Drone 的 OSD 遥测数据。
 * <p>仅当 {@link DeviceState#isOnline()} 为 true 时上报。</p>
 * <p>字段构造委托给 {@link DockOsdBuilder}（机场字段集）和 {@link DroneOsdBuilder}（飞行器字段集），
 * 所有 Dock 版本统一使用 snake_case 字段命名，字段名直接引用 {@link ltd.cdmi.dji.cloudapi.sdk.telemetry.OsdField} 枚举。
 * 两者通过 {@link OsdContext} 协作。</p>
 */
@Component
public class DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);
    /** OSD 上报频率：0.5Hz = 每 2 秒一次 */
    private static final long OSD_INTERVAL_SECONDS = 2;

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final ObjectMapper objectMapper;
    private final RuntimeConfig runtimeConfig;
    private final List<DockOsdBuilder> dockBuilders;
    private final List<DroneOsdBuilder> droneBuilders;
    private final List<RcOsdBuilder> rcBuilders;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final DockTopicSchema dockTopicSchema;
    private final AiSimulator aiSimulator;

    private ScheduledExecutorService scheduler;

    public DeviceSimulator(SimulatorProperties props, MqttClientManager mqtt, DeviceState state,
                           ObjectMapper objectMapper, RuntimeConfig runtimeConfig,
                           List<DockOsdBuilder> dockBuilders,
                           List<DroneOsdBuilder> droneBuilders,
                           List<RcOsdBuilder> rcBuilders,
                           DiagnosticLogRecorder diagnosticRecorder,
                           DockTopicSchema dockTopicSchema,
                           AiSimulator aiSimulator) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.runtimeConfig = runtimeConfig;
        this.dockBuilders = dockBuilders;
        this.droneBuilders = droneBuilders;
        this.rcBuilders = rcBuilders;
        this.diagnosticRecorder = diagnosticRecorder;
        this.dockTopicSchema = dockTopicSchema;
        this.aiSimulator = aiSimulator;
    }

    /**
     * 根据当前 dockType 选择机场 OSD Builder。
     * <p>使用 {@link DockOsdBuilder#supports(DockModel)} 匹配。</p>
     */
    private DockOsdBuilder selectDockBuilder() {
        DockModel dockType = runtimeConfig.getDockType();
        for (DockOsdBuilder b : dockBuilders) {
            if (b.supports(dockType)) {
                return b;
            }
        }
        return dockBuilders.get(0); // 兜底
    }

    /**
     * 根据当前 droneType 选择飞行器 OSD Builder。
     */
    private DroneOsdBuilder selectDroneBuilder() {
        DroneModel droneType = runtimeConfig.getDroneType();
        for (DroneOsdBuilder b : droneBuilders) {
            if (b.supports(droneType)) {
                return b;
            }
        }
        return droneBuilders.get(0); // 兜底
    }

    /**
     * 根据当前 controllerType 选择遥控器 OSD Builder（Pilot 模式）。
     */
    private RcOsdBuilder selectRcBuilder() {
        RcModel controllerType = runtimeConfig.getControllerType();
        for (RcOsdBuilder b : rcBuilders) {
            if (b.supports(controllerType)) {
                return b;
            }
        }
        return rcBuilders.get(0); // 兜底
    }

    @PostConstruct
    public void init() {
        // 初始化无人机位置为机场位置
        state.setDroneLatitude(runtimeConfig.getLocationLatitude());
        state.setDroneLongitude(runtimeConfig.getLocationLongitude());
        state.setDroneElevation(runtimeConfig.getLocationHeight());

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "osd-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::publishOsd, 2, OSD_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("OSD 上报调度器已启动，频率 {}Hz", 1.0 / OSD_INTERVAL_SECONDS);

        // M-2 诊断日志：Dock OSD 分多条推送的字段分组方案为推断（DJI 文档仅提供 Dock1 示例，Dock3 具体分组未明确）
        String inference = "Dock OSD 分多条推送的字段分组方案：DJI 文档明确「机场的设备属性推送是分多条推送的」并提供 Dock1 示例（3 组），"
            + "但 Dock3 具体字段分组未明确。模拟器按 Dock1 示例的分组模式推断 Dock3 分组（Group1=电源/电池/保养/统计, Group2=任务/图传/媒体, Group3=位置/环境/机械/子设备），待真机验证";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "dock_osd_multi_message", inference);
        log.warn("[M-2] Dock OSD 分多条推送字段分组为推断（基于 Dock1 示例适配 Dock3），待真机验证");

        // M-2 诊断日志：飞行器 OSD track_id 字段文档未明确，按真机 OSD 示例上报
        String trackIdInference = "飞行器 OSD track_id 字段：DJI M4D/M30 properties 文档字段列表未明确显示 track_id，"
            + "但真机 M30 OSD 示例中包含此字段（值为空字符串）。模拟器按真机示例上报 track_id，待真机验证";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "drone_osd_track_id", trackIdInference);
        log.warn("[M-2] 飞行器 OSD track_id 字段文档未明确，按真机示例上报，待真机验证");

        // M-2 诊断日志：飞行器负载属性上报，measure_target_* 字段为模拟值（无测距场景）
        String payloadInference = "飞行器 OSD 负载属性上报：DJI 文档「负载属性上报」明确以负载索引（{type-subtype-gimbalIndex}）为 key 上报相机属性，"
            + "OSD（pushMode=0）包含 gimbal_pitch/roll/yaw + measure_target_* + zoom_factor + thermal_*（仅 thermal 机型）。"
            + "payload_index 是 pushMode=1（state topic），不在 OSD 中；version 字段文档中不存在，不上报。"
            + "模拟器不模拟测距场景，measure_target_error_state=3（NO_SIGNAL），其余 measure_target_* 为 0。负载索引按机型自动匹配（M30→52-0-0, M3D→80-0-0, M4D→98-0-0），待真机验证。"
            + "key 占位符推定（2026-09-04，TC-PAYLOAD-027）：M3D properties 文档 Column 名 type_subtype_gimbalindex 为 {type-subtype-gimbalindex} 占位符简写，"
            + "实际 key 应为负载索引枚举值（M30/Pilot properties 明确写 {type-subtype-gimbalindex} 且描述「与字段 payload_index 数值一致」，"
            + "Dock2 官网 Example 实证 key=\"52-0-0\"）。M3D 文档无 Example，key 取值依据三份文档描述语义一致推定，待真机验证：Dock3 飞行器 OSD 负载属性 key 为相机枚举值而非字面量";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "drone_osd_payload", payloadInference);
        log.warn("[M-2] 飞行器负载属性 measure_target_* 为模拟值 + key 为负载索引枚举值（占位符推定），待真机验证");

        // M-2 诊断日志：Dock1 OSD 示例用 air_conditioner_mode（标量），但属性列表用 air_conditioner（struct）
        String acInference = "机场 OSD air_conditioner 字段：Dock1/Dock2/Dock3 属性列表均标注为 struct（含 air_conditioner_state + switch_time），"
            + "但 Dock1 OSD 结构示例误用标量 air_conditioner_mode。模拟器以属性列表为准，三版均上报 struct air_conditioner，待真机验证";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "dock_osd_air_conditioner", acInference);
        log.warn("[M-2] Dock1 OSD 示例用 air_conditioner_mode 标量，属性列表用 air_conditioner struct，以属性列表为准，待真机验证");

        // M-2 诊断日志：Dock1 sub_device 字段名 product_type（属性列表）vs device_model_key（OSD 示例）
        String sdmInference = "机场 OSD sub_device 字段名：Dock1 属性列表标注 product_type，但 Dock1/Dock2/Dock3 OSD 示例均用 device_model_key。"
            + "模拟器三版统一使用 device_model_key，待真机验证";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "dock_osd_sub_device_model_key", sdmInference);
        log.warn("[M-2] Dock1 sub_device 字段名 product_type（属性列表）vs device_model_key（OSD 示例），三版统一用 device_model_key，待真机验证");

        // M-2 诊断日志：唯一 SN 模式生成的 SN 结构为推断（DJI Cloud API 未定义 SN 内部编码规则）
        if (runtimeConfig.isSnUniqueEnabled()) {
            String snInference = "唯一 SN 模式：模拟器生成的 SN = defaultSn 前缀 + 随机 [0-9A-Z] 后缀（长度/字符集与真机 SN 同构，"
                + "前缀保留型号标识）。DJI Cloud API 未定义 SN 内部编码规则，决策依据为「平台将 SN 作为不透明字符串处理」。"
                + "待真机验证：平台（hivemind）对 SN 结构无格式校验、同长度非出厂 SN 可正常注册上线";
            diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "sn_unique_mode", snInference);
            log.warn("[M-2] 唯一 SN 模式生成的 SN 结构（defaultSn 前缀+随机后缀）为推断，待真机验证平台对 SN 无结构校验");
        }
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 构造并发布 Dock + Drone OSD 报文。
     * <p>异常隔离策略：网关 OSD / 飞行器 OSD / DRC 事件各自独立 try-catch，
     * 任一推送异常不影响其他推送，且捕获 Throwable（含 Error）防止调度器因未捕获异常停止。</p>
     */
    public void publishOsd() {
        if (!state.isOnline()) {
            return;
        }
        OsdContext ctx = new OsdContext(state, props, runtimeConfig);
        String gatewaySn = runtimeConfig.getGatewaySn();

        // 网关 OSD（始终推送）：Dock 模式推送 Dock OSD（分多条），Pilot 模式推送 RC OSD（单条）
        try {
            String gatewayOsdTopic = dockTopicSchema.topic(dockTopicSchema.osd(), gatewaySn);
            if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
                mqtt.publish(gatewayOsdTopic, wrapOsd(selectRcBuilder().buildRcOsd(ctx), gatewaySn));
            } else {
                // 对齐 DJI 文档「机场的设备属性推送是分多条推送的」（Dock3 properties 文档「设备属性推送」章节）
                for (Map<String, Object> data : selectDockBuilder().buildDockOsd(ctx)) {
                    mqtt.publish(gatewayOsdTopic, wrapOsd(data, gatewaySn));
                }
            }
        } catch (Throwable t) {
            log.error("网关 OSD 上报异常: {}", t.getMessage(), t);
        }

        // Drone OSD（仅飞行器激活时推送，休眠状态不推送）
        // 独立 try-catch：飞行器 OSD 构造异常不影响网关 OSD 和调度器
        if (state.isDroneActivated()) {
            try {
                String droneOsdTopic = dockTopicSchema.topic(dockTopicSchema.osd(), runtimeConfig.getDroneSn());
                mqtt.publish(droneOsdTopic, wrapOsd(selectDroneBuilder().buildDroneOsd(ctx), gatewaySn));
            } catch (Throwable t) {
                log.error("飞行器 OSD 上报异常: {}", t.getMessage(), t);
            }
        }

        // DRC 事件推送（仅在 DRC 模式激活时）
        if (state.getDrcState() != 0) {
            try {
                publishDrcEvents();
            } catch (Throwable t) {
                log.error("DRC 事件推送异常: {}", t.getMessage(), t);
            }
        }
    }

    /**
     * 推送 DRC 事件到 drc/up（仅 DRC 模式激活时调用）。
     * <p>DRC 消息格式：{@code {method, data, seq}}，与 OSD 格式不同。</p>
     * <p>推送频率与 OSD 一致（0.5Hz），由 {@link #publishOsd()} 统一调度。</p>
     */
    private void publishDrcEvents() {
        String drcUpTopic = dockTopicSchema.topic(dockTopicSchema.drcUp(), runtimeConfig.getGatewaySn());

        // drc_drone_state_push：飞行器状态
        Map<String, Object> droneState = new LinkedHashMap<>();
        droneState.put("mode_code", state.getDroneModeCode());
        droneState.put("stealth_state", state.isStealthState() ? 1 : 0);
        droneState.put("night_lights_state", state.isNightLightsState() ? 1 : 0);
        droneState.put("landing_type", 0);
        droneState.put("landing_protection_type", 0);
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_DRONE_STATE_PUSH.methodName(), droneState));

        // drc_camera_state_push：相机状态
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_CAMERA_STATE_PUSH.methodName(), buildDrcCameraState()));

        // drc_camera_osd_info_push：摄像头 OSD
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_CAMERA_OSD_INFO_PUSH.methodName(), buildDrcCameraOsdInfo()));

        // hsi_info_push：避障信息上报
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.HSI_INFO_PUSH.methodName(), buildHsiInfo()));

        // delay_info_push：图传链路延时信息上报
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DELAY_INFO_PUSH.methodName(), buildDelayInfo()));

        // osd_info_push：高频 osd 信息上报
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.OSD_INFO_PUSH.methodName(), buildOsdInfo()));

        // Phase 4: PSDK + AI 事件推送（Dock3 专属）。
        // SDK DrcMethod/DrcUpMethod 明确 Dock3 独有，Dock1/Dock2 在 DRC 模式下不推送，
        // 避免多报 Dock3 专属状态导致平台解析异常。对齐 TDD-SPEC TC-DRC-DOCK3-004/005。
        if (runtimeConfig.getDockType() == DockModel.DOCK3) {
            publishPsdkAndAiEvents(drcUpTopic);
        }
    }

    /**
     * 推送 PSDK 和 AI 相关事件（Dock3 专属）。
     * <p>包含：浮窗推送、探照灯/喊话器状态上报、UI资源包、AI状态。</p>
     */
    private void publishPsdkAndAiEvents(String drcUpTopic) {
        // drc_psdk_floating_window_text：PSDK 浮窗推送
        Map<String, Object> floatWindow = new LinkedHashMap<>();
        floatWindow.put("psdk_index", 0);
        floatWindow.put("floating_window_text", "");
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_PSDK_FLOATING_WINDOW_TEXT.methodName(), floatWindow));

        // drc_psdk_state_info：探照灯状态上报
        Map<String, Object> lightState = new LinkedHashMap<>();
        lightState.put("psdk_index", 1);
        lightState.put("psdk_type", 5);  // 5=大疆自研
        lightState.put("psdk_name", "Searchlight");
        lightState.put("psdk_sn", "psdk_light_sn");
        lightState.put("psdk_version", "1.0.0");
        lightState.put("psdk_lib_version", "1.0.0");
        Map<String, Object> light = new LinkedHashMap<>();
        light.put("work_mode", state.getLightMode());
        light.put("brightness", state.getLightBrightness());
        light.put("calibration_status", 0);  // 0=校准完成
        light.put("calibration_progress", 100);
        light.put("left_value", state.getLightLeftAngle());
        light.put("right_value", state.getLightRightAngle());
        light.put("wide_field_mode", false);
        light.put("light_gimbal_control", false);
        lightState.put("light", light);
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_PSDK_STATE_INFO.methodName(), lightState));

        // drc_psdk_state_info：喊话器状态上报（设备标识见 PsdkConstants，与 state topic 共用同一真相源）
        Map<String, Object> speakerState = new LinkedHashMap<>();
        speakerState.put("psdk_index", PsdkConstants.SPEAKER_INDEX);
        speakerState.put("psdk_type", 5);
        speakerState.put("psdk_name", PsdkConstants.SPEAKER_NAME);
        speakerState.put("psdk_sn", PsdkConstants.SPEAKER_SN);
        speakerState.put("psdk_version", "1.0.0");
        speakerState.put("psdk_lib_version", "1.0.0");
        Map<String, Object> speaker = new LinkedHashMap<>();
        speaker.put("work_mode", 0);  // 0=TTS模式
        speaker.put("play_mode", state.getSpeakerPlayMode());
        speaker.put("system_state", state.isSpeakerPlaying() ? 2 : 0);  // 2=播放中, 0=空闲中
        speaker.put("play_volume", state.getSpeakerVolume());
        speaker.put("play_file_name", "");
        speaker.put("play_file_md5", "");
        speaker.put("tts_volume", state.getSpeakerVolume());
        speaker.put("tts_type", 0);  // 0=男声
        speaker.put("tts_language", 0);  // 0=中文
        speaker.put("tts_speed", 50);
        speakerState.put("speaker", speaker);
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_PSDK_STATE_INFO.methodName(), speakerState));

        // drc_speaker_play_progress：喊话器播放进度（仅在播放中时推送）
        if (state.isSpeakerPlaying()) {
            Map<String, Object> playProgress = new LinkedHashMap<>();
            playProgress.put("psdk_index", PsdkConstants.SPEAKER_INDEX);
            playProgress.put("result", 0);  // 0=成功
            playProgress.put("status", "success");  // 模拟器播放立即完成
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("step_key", "play");
            progress.put("percent", 100);
            playProgress.put("progress", progress);
            playProgress.put("md5", "");
            mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_SPEAKER_PLAY_PROGRESS.methodName(), playProgress));
        }

        // drc_psdk_ui_resource：PSDK UI 资源包
        Map<String, Object> uiResource = new LinkedHashMap<>();
        uiResource.put("psdk_index", 0);
        uiResource.put("psdk_ready", 1);
        uiResource.put("object_key", "psdk_config/0/ui_resource.tar.gz");
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_PSDK_UI_RESOURCE.methodName(), uiResource));

        // drc_ai_info_push：AI 状态上报
        // 状态由 AiSimulator 维护，确保定时推送与指令触发的推送使用同一数据源
        Map<String, Object> aiInfo = aiSimulator.buildAiInfo();
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_AI_INFO_PUSH.methodName(), aiInfo));
    }

    /**
     * 当前生效的负载索引（TC-DRC-039/040）。
     * <p>平台指令（camera_mode_switch 等）指定的 payloadIndex 优先；
     * 未指定（null）时按机型动态解析主相机，与 live_capacity、drone OSD payloads 同源。</p>
     */
    private String currentPayloadIndex() {
        String specified = state.getPayloadIndex();
        if (specified != null) return specified;
        return DefaultCameraResolver.requireDefaultCameraIndex(runtimeConfig.getDroneType(), "DRC 遥测");
    }

    /**
     * 构造 drc_camera_state_push 数据。
     * <p>详见 DJI Cloud API Dock3 remote-control 相机状态上报。</p>
     */
    private Map<String, Object> buildDrcCameraState() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payload_index", currentPayloadIndex());

        // camera_state
        Map<String, Object> cameraState = new LinkedHashMap<>();
        cameraState.put("camera_mode", state.getCameraMode());
        cameraState.put("interval_photo_interval", state.getIntervalPhotoInterval());
        cameraState.put("video_resolution", state.getVideoResolution());
        cameraState.put("linkage_zoom_state", state.isLinkageZoomState() ? 1 : 0);
        cameraState.put("photo_size", state.getPhotoSize());
        cameraState.put("record_time", state.getRecordTime());
        cameraState.put("recording_state", state.getRecordingState());
        cameraState.put("photo_state", state.getPhotoState());
        cameraState.put("photo_format", 7);                                        // 拍照格式：7=RJPEG（Dock3 remote-control 文档枚举，无对应指令可变更，固定值）
        cameraState.put("remain_photo_num", state.getRemainPhotoNum());
        cameraState.put("remain_record_duration", state.getRemainRecordDuration());

        // night_mode_settings（Dock3 特有，由 Phase 3 指令动态控制）
        Map<String, Object> nightMode = new LinkedHashMap<>();
        nightMode.put("night_mode", state.getNightMode());
        nightMode.put("denoise_level", state.getDenoiseLevel());
        nightMode.put("night_vision_enable", state.isNightVisionEnable());
        nightMode.put("infrared_fill_light_enable", state.isInfraredFillLightEnable());
        nightMode.put("night_scene_mode_suggestion", 1);
        nightMode.put("is_working", 1);
        cameraState.put("night_mode_settings", nightMode);

        data.put("camera_state", cameraState);

        // media_storage
        Map<String, Object> mediaStorage = new LinkedHashMap<>();
        mediaStorage.put("photo_storage_settings", List.of("current", "ir"));
        mediaStorage.put("video_storage_settings", List.of("current", "ir"));
        data.put("media_storage", mediaStorage);

        return data;
    }

    /**
     * 构造 drc_camera_osd_info_push 数据。
     * <p>详见 DJI Cloud API Dock3 remote-control 摄像头osd推送。</p>
     */
    private Map<String, Object> buildDrcCameraOsdInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payload_index", currentPayloadIndex());

        // wide_lense：广角镜头（默认值，暂不可配）
        Map<String, Object> wideLense = new LinkedHashMap<>();
        wideLense.put("wide_exposure_mode", 1);
        wideLense.put("wide_iso", 8);
        wideLense.put("wide_shutter_speed", 45);
        wideLense.put("wide_exposure_value", 16);
        wideLense.put("wide_aperture_value", 10);
        data.put("wide_lense", wideLense);

        // zoom_lense：变焦镜头
        Map<String, Object> zoomLense = new LinkedHashMap<>();
        zoomLense.put("zoom_exposure_mode", 1);
        zoomLense.put("zoom_iso", 8);
        zoomLense.put("zoom_shutter_speed", 45);
        zoomLense.put("zoom_exposure_value", 16);
        zoomLense.put("zoom_focus_mode", 0);
        zoomLense.put("zoom_focus_value", state.getZoomFocusValue());
        zoomLense.put("zoom_max_focus_value", state.getZoomMaxFocusValue());
        zoomLense.put("zoom_min_focus_value", state.getZoomMinFocusValue());
        zoomLense.put("zoom_calibrate_farthest_focus_value", 34);
        zoomLense.put("zoom_calibrate_nearest_focus_value", 64);
        zoomLense.put("zoom_focus_state", 0);
        zoomLense.put("zoom_factor", state.getZoomFactor());
        zoomLense.put("zoom_aperture_value", 10);
        data.put("zoom_lense", zoomLense);

        // measure_target：激光测距
        Map<String, Object> measureTarget = new LinkedHashMap<>();
        measureTarget.put("measure_target_longitude", state.getMeasureTargetLongitude());
        measureTarget.put("measure_target_latitude", state.getMeasureTargetLatitude());
        measureTarget.put("measure_target_altitude", state.getMeasureTargetAltitude());
        measureTarget.put("measure_target_distance", state.getMeasureTargetDistance());
        measureTarget.put("measure_target_error_state", 1);
        data.put("measure_target", measureTarget);

        // ir_lense：红外信息
        Map<String, Object> irLense = new LinkedHashMap<>();
        irLense.put("screen_split_enable", false);
        irLense.put("ir_zoom_factor", 2);
        irLense.put("thermal_supported_palette_styles", List.of(1, 6, 11));       // 支持的调色板：1=白热, 6=黑热, 11=彩虹（能力集数组，固定值）
        irLense.put("thermal_current_palette_style", 11);
        irLense.put("thermal_gain_mode", 2);
        irLense.put("thermal_isotherm_state", 0);
        irLense.put("thermal_isotherm_upper_limit", 150);
        irLense.put("thermal_isotherm_lower_limit", -20);
        irLense.put("thermal_global_temperature_min", state.getThermalGlobalTempMin());
        irLense.put("thermal_global_temperature_max", state.getThermalGlobalTempMax());
        data.put("ir_lense", irLense);

        // liveview：直播视图区域
        Map<String, Object> liveview = new LinkedHashMap<>();
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("left", 0.4324);
        region.put("top", 0.4332);
        region.put("right", 0.5639);
        region.put("bottom", 0.5609);
        liveview.put("liveview_world_region", region);
        data.put("liveview", liveview);

        return data;
    }

    /**
     * 构造 hsi_info_push 避障信息（DJI Dock3 remote-control hsi_info_push）。
     * <p>模拟器默认：所有避障开关启用且正常工作，around_distances 上报空数组（无障碍物）。</p>
     */
    private Map<String, Object> buildHsiInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("up_distance", 10000);
        data.put("down_distance", 10000);
        data.put("up_enable", true);
        data.put("up_work", true);
        data.put("down_enable", true);
        data.put("down_work", true);
        data.put("left_enable", true);
        data.put("left_work", true);
        data.put("right_enable", true);
        data.put("right_work", true);
        data.put("front_enable", true);
        data.put("front_work", true);
        data.put("back_enable", true);
        data.put("back_work", true);
        data.put("vertical_enable", true);
        data.put("vertical_work", true);
        data.put("horizontal_enable", true);
        data.put("horizontal_work", true);
        // 空数组表示任意角度都无障碍物（DJI 文档明确）
        data.put("around_distances", List.of());
        return data;
    }

    /**
     * 构造 delay_info_push 图传链路延时信息（DJI Dock3 remote-control delay_info_push）。
     * <p>模拟器默认：sdr_cmd_delay=10ms，广角+变焦两路码流延时 60/80ms。</p>
     */
    private Map<String, Object> buildDelayInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sdr_cmd_delay", 10);
        List<Map<String, Object>> delayList = new ArrayList<>();
        String droneSn = runtimeConfig.getDroneSn();
        String cameraIndex = currentPayloadIndex();
        delayList.add(buildLiveviewDelay(droneSn + "/" + cameraIndex + "/normal-0", 60));
        delayList.add(buildLiveviewDelay(droneSn + "/" + cameraIndex + "/zoom-0", 80));
        data.put("liveview_delay_list", delayList);
        return data;
    }

    /** 构造单路码流延时记录 */
    private Map<String, Object> buildLiveviewDelay(String videoId, int delayMs) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("video_id", videoId);
        entry.put("liveview_delay_time", delayMs);
        return entry;
    }

    /**
     * 构造 osd_info_push 高频 OSD 信息（DJI Dock3 remote-control osd_info_push）。
     * <p>从 DeviceState 读取飞行器位置/姿态/速度/云台角度。
     * 云台角度由 drc_gimbal_reset / drc_camera_screen_drag 指令更新（对齐 TDD-SPEC TC-DRC-GIMBAL-004）。</p>
     */
    private Map<String, Object> buildOsdInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attitude_head", state.getAttitudeYaw());
        data.put("latitude", state.getDroneLatitude());
        data.put("longitude", state.getDroneLongitude());
        data.put("height", state.getDroneHeight());
        data.put("elevation", state.getDroneElevation());
        data.put("speed_x", state.getHorizontalSpeed());
        data.put("speed_y", 0.0);
        data.put("speed_z", state.getVerticalSpeed());
        data.put("gimbal_pitch", state.getGimbalPitch());
        data.put("gimbal_roll", state.getGimbalRoll());
        data.put("gimbal_yaw", state.getGimbalYaw());
        return data;
    }

    /**
     * 包装 OSD 数据为 DJI Cloud API 协议格式。
     * <p>格式：{@code {bid, tid, timestamp, gateway, data}}</p>
     */
    private String wrapOsd(Map<String, Object> data, String gatewaySn) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("gateway", gatewaySn);
        envelope.put("data", data);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("OSD JSON 序列化失败: {}", e.getMessage(), e);
            return "{}";
        }
    }
}
