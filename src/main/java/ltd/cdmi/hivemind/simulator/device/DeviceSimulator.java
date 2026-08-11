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
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.mqtt.DrcMessage;
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
 * 设备模拟器：0.5Hz 构造并发布 Dock + Drone 的 OSD 遥测数据。
 * <p>仅当 {@link DeviceState#isOnline()} 为 true 时上报。</p>
 * <p>字段构造委托给 {@link DockOsdBuilder}（机场字段集）和 {@link DroneOsdBuilder}（飞行器字段集），
 * 字段命名风格由 {@link OsdStrategy} 按 dockType 动态切换（Dock3 用 snake_case，Dock1/Dock2 用 camelCase）。
 * 三者通过 {@link OsdContext} 协作，实现"字段集"与"命名风格"两个维度解耦。</p>
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
    private final List<OsdStrategy> strategies;
    private final List<DockOsdBuilder> dockBuilders;
    private final List<DroneOsdBuilder> droneBuilders;

    private ScheduledExecutorService scheduler;

    public DeviceSimulator(SimulatorProperties props, MqttClientManager mqtt, DeviceState state,
                           ObjectMapper objectMapper, RuntimeConfig runtimeConfig,
                           List<OsdStrategy> strategies,
                           List<DockOsdBuilder> dockBuilders,
                           List<DroneOsdBuilder> droneBuilders) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.runtimeConfig = runtimeConfig;
        this.strategies = strategies;
        this.dockBuilders = dockBuilders;
        this.droneBuilders = droneBuilders;
    }

    /**
     * 根据当前 dockType 返回对应的 OSD 命名策略。
     * <p>DOCK3 → Dock3OsdStrategy（snake_case）；DOCK1/DOCK2 → Dock1OsdStrategy（camelCase）。</p>
     */
    private OsdStrategy currentStrategy() {
        DeviceType dockType = runtimeConfig.getDockType();
        String targetVersion = (dockType == DeviceType.DOCK3) ? "dock3" : "dock1";
        for (OsdStrategy s : strategies) {
            if (s.version().equals(targetVersion)) {
                return s;
            }
        }
        return strategies.get(0); // 兜底
    }

    /**
     * 根据当前 dockType 选择机场 OSD Builder。
     * <p>使用 {@link DockOsdBuilder#supports(DeviceType)} 匹配，与 version() 解耦：
     * Dock1/Dock2 共用 "dock1" 策略但字段集不同，需通过 supports 精确匹配。</p>
     */
    private DockOsdBuilder selectDockBuilder() {
        DeviceType dockType = runtimeConfig.getDockType();
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
        DeviceType droneType = runtimeConfig.getDroneType();
        for (DroneOsdBuilder b : droneBuilders) {
            if (b.supports(droneType)) {
                return b;
            }
        }
        return droneBuilders.get(0); // 兜底
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
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 构造并发布 Dock + Drone OSD 报文。
     */
    public void publishOsd() {
        if (!state.isOnline()) {
            return;
        }
        try {
            OsdContext ctx = new OsdContext(state, props, runtimeConfig, currentStrategy());

            // Dock OSD（始终推送）
            String dockOsdTopic = TopicConstants.topic(TopicConstants.OSD, runtimeConfig.getDockSn());
            mqtt.publish(dockOsdTopic, wrapOsd(selectDockBuilder().buildDockOsd(ctx)));

            // Drone OSD（仅飞行器激活时推送，休眠状态不推送）
            if (state.isDroneActivated()) {
                String droneOsdTopic = TopicConstants.topic(TopicConstants.OSD, runtimeConfig.getDroneSn());
                mqtt.publish(droneOsdTopic, wrapOsd(selectDroneBuilder().buildDroneOsd(ctx)));
            }

            // DRC 事件推送（仅在 DRC 模式激活时）
            if (state.getDrcState() != 0) {
                publishDrcEvents();
            }
        } catch (Exception e) {
            log.error("OSD 上报异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 推送 DRC 事件到 drc/up（仅 DRC 模式激活时调用）。
     * <p>DRC 消息格式：{@code {method, data, seq}}，与 OSD 格式不同。</p>
     * <p>推送频率与 OSD 一致（0.5Hz），由 {@link #publishOsd()} 统一调度。</p>
     */
    private void publishDrcEvents() {
        String drcUpTopic = TopicConstants.topic(TopicConstants.DRC_UP, runtimeConfig.getDockSn());

        // drc_drone_state_push：飞行器状态
        Map<String, Object> droneState = new LinkedHashMap<>();
        droneState.put("mode_code", state.getDroneModeCode());
        droneState.put("stealth_state", state.isStealthState() ? 1 : 0);
        droneState.put("night_lights_state", state.isNightLightsState() ? 1 : 0);
        mqtt.publishJson(drcUpTopic, DrcMessage.event("drc_drone_state_push", droneState));

        // drc_camera_state_push：相机状态
        mqtt.publishJson(drcUpTopic, DrcMessage.event("drc_camera_state_push", buildDrcCameraState()));

        // drc_camera_osd_info_push：摄像头 OSD
        mqtt.publishJson(drcUpTopic, DrcMessage.event("drc_camera_osd_info_push", buildDrcCameraOsdInfo()));

        // Phase 4: PSDK + AI 事件推送（Dock3 专属）
        publishPsdkAndAiEvents(drcUpTopic);
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
        mqtt.publishJson(drcUpTopic, DrcMessage.event("drc_psdk_floating_window_text", floatWindow));

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
        mqtt.publishJson(drcUpTopic, DrcMessage.event("drc_psdk_state_info", lightState));

        // drc_psdk_state_info：喊话器状态上报
        Map<String, Object> speakerState = new LinkedHashMap<>();
        speakerState.put("psdk_index", 2);
        speakerState.put("psdk_type", 5);
        speakerState.put("psdk_name", "Speaker");
        speakerState.put("psdk_sn", "psdk_speaker_sn");
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
        mqtt.publishJson(drcUpTopic, DrcMessage.event("drc_psdk_state_info", speakerState));

        // drc_speaker_play_progress：喊话器播放进度（仅在播放中时推送）
        if (state.isSpeakerPlaying()) {
            Map<String, Object> playProgress = new LinkedHashMap<>();
            playProgress.put("psdk_index", 2);
            playProgress.put("result", 0);  // 0=成功
            playProgress.put("status", "success");  // 模拟器播放立即完成
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("step_key", "play");
            progress.put("percent", 100);
            playProgress.put("progress", progress);
            playProgress.put("md5", "");
            mqtt.publishJson(drcUpTopic, DrcMessage.event("drc_speaker_play_progress", playProgress));
        }

        // drc_psdk_ui_resource：PSDK UI 资源包
        Map<String, Object> uiResource = new LinkedHashMap<>();
        uiResource.put("psdk_index", 0);
        uiResource.put("psdk_ready", 1);
        uiResource.put("object_key", "psdk_config/0/ui_resource.tar.gz");
        mqtt.publishJson(drcUpTopic, DrcMessage.event("drc_psdk_ui_resource", uiResource));

        // drc_ai_info_push：AI 状态上报
        Map<String, Object> aiInfo = new LinkedHashMap<>();
        aiInfo.put("identify_on", 0);  // 0=关闭
        aiInfo.put("spotlight_zoom_on", 0);  // 0=关闭
        Map<String, Object> aiSpotlightZoom = new LinkedHashMap<>();
        aiSpotlightZoom.put("state", 0);  // 0=空闲
        aiSpotlightZoom.put("state_reason", 0);  // 0=正常
        aiInfo.put("ai_spotlight_zoom", aiSpotlightZoom);
        // ai_model_list
        List<Map<String, Object>> aiModelList = new ArrayList<>();
        Map<String, Object> model1 = new LinkedHashMap<>();
        model1.put("index", 0);
        model1.put("signed_name", "DJI");
        aiModelList.add(model1);
        aiInfo.put("ai_model_list", aiModelList);
        // selected_ai_model
        Map<String, Object> selectedModel = new LinkedHashMap<>();
        selectedModel.put("index", 0);
        selectedModel.put("score", 100);
        selectedModel.put("score_mode", 1);  // 1=计数模式
        selectedModel.put("image_source", List.of(1, 2, 3));  // 广角/变焦/红外
        selectedModel.put("digital_effect", List.of(0, 1, 2));
        selectedModel.put("filters", List.of(1, 2, 3));
        List<Map<String, Object>> labels = new ArrayList<>();
        Map<String, Object> label1 = new LinkedHashMap<>();
        label1.put("index", 0);
        label1.put("name", "摩托车");
        labels.add(label1);
        Map<String, Object> label2 = new LinkedHashMap<>();
        label2.put("index", 1);
        label2.put("name", "自行车");
        labels.add(label2);
        selectedModel.put("labels", labels);
        aiInfo.put("selected_ai_model", selectedModel);
        mqtt.publishJson(drcUpTopic, DrcMessage.event("drc_ai_info_push", aiInfo));
    }

    /**
     * 构造 drc_camera_state_push 数据。
     * <p>详见 DJI Cloud API Dock3 remote-control 相机状态上报。</p>
     */
    private Map<String, Object> buildDrcCameraState() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payload_index", state.getPayloadIndex());

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
        data.put("payload_index", state.getPayloadIndex());

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
     * 包装 OSD 数据为 DJI Cloud API 协议格式。
     * <p>格式：{@code {"bid":"...","data":{...},"timestamp":...,"version":"dock3"}}</p>
     */
    private String wrapOsd(Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("data", data);
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("version", currentStrategy().version());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("OSD JSON 序列化失败: {}", e.getMessage(), e);
            return "{}";
        }
    }
}
