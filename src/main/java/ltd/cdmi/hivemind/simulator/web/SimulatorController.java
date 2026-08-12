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

package ltd.cdmi.hivemind.simulator.web;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.device.PilotOnlineService;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.FfmpegWhipPusher;
import ltd.cdmi.hivemind.simulator.handler.FfmpegInstaller;
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.handler.HmsSimulator;
import ltd.cdmi.hivemind.simulator.handler.LiveStreamSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟器 REST API，供 Web 控制台调用。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class SimulatorController {

    private final DockOnlineService onlineService;
    private final PilotOnlineService pilotOnlineService;
    private final DeviceState state;
    private final MqttClientManager mqtt;
    private final WaylineTaskSimulator waylineSimulator;
    private final LiveStreamSimulator liveSimulator;
    private final MediaUploadSimulator mediaSimulator;
    private final HmsSimulator hmsSimulator;
    private final FlightCommandSimulator flightCommandSimulator;
    private final FfmpegWhipPusher ffmpegPusher;
    private final FfmpegInstaller ffmpegInstaller;
    private final RuntimeConfig runtimeConfig;
    private final SimulatorProperties props;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final CoverageRecorder coverageRecorder;

    public SimulatorController(DockOnlineService onlineService, PilotOnlineService pilotOnlineService,
                               DeviceState state,
                               MqttClientManager mqtt, WaylineTaskSimulator waylineSimulator,
                               LiveStreamSimulator liveSimulator, MediaUploadSimulator mediaSimulator,
                               HmsSimulator hmsSimulator, FlightCommandSimulator flightCommandSimulator,
                               FfmpegWhipPusher ffmpegPusher, FfmpegInstaller ffmpegInstaller,
                               RuntimeConfig runtimeConfig, SimulatorProperties props,
                               DiagnosticLogRecorder diagnosticRecorder,
                               CoverageRecorder coverageRecorder) {
        this.onlineService = onlineService;
        this.pilotOnlineService = pilotOnlineService;
        this.state = state;
        this.mqtt = mqtt;
        this.waylineSimulator = waylineSimulator;
        this.liveSimulator = liveSimulator;
        this.mediaSimulator = mediaSimulator;
        this.hmsSimulator = hmsSimulator;
        this.flightCommandSimulator = flightCommandSimulator;
        this.ffmpegPusher = ffmpegPusher;
        this.ffmpegInstaller = ffmpegInstaller;
        this.runtimeConfig = runtimeConfig;
        this.props = props;
        this.diagnosticRecorder = diagnosticRecorder;
        this.coverageRecorder = coverageRecorder;
    }

    // ==================== 设备控制 ====================

    /**
     * 设备上线。
     * <p>Dock 模式：支持 skip_register 跳过注册（用于已注册设备的开机自动重连）。
     * <p>Pilot 模式：始终跳过注册流程，直接 update_topo 上线。
     */
    @PostMapping("/online")
    public Map<String, Object> online(@RequestBody(required = false) Map<String, Object> body) {
        boolean skipRegister = body != null
                && Boolean.parseBoolean(String.valueOf(body.getOrDefault("skip_register", false)));
        DockOnlineService.OnlineResult onlineResult;
        if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
            // Pilot 模式：跳过注册流程，直接 update_topo 上线
            onlineResult = pilotOnlineService.online();
        } else {
            // Dock 模式：支持 skip_register 跳过注册（用于已注册设备的开机自动重连）
            onlineResult = skipRegister ? onlineService.onlineOnly() : onlineService.online();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", onlineResult.success());
        result.put("code", onlineResult.code());
        result.put("online", state.isOnline());
        result.put("mqtt_connected", mqtt.isConnected());
        return result;
    }

    /** 设备下线：停止 OSD + update_topo 空列表 + 断开 MQTT */
    @PostMapping("/offline")
    public Map<String, Object> offline() {
        if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
            pilotOnlineService.offline();
        } else {
            onlineService.offline();
        }
        mqtt.disconnect();
        diagnosticRecorder.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("online", state.isOnline());
        result.put("mqtt_connected", mqtt.isConnected());
        return result;
    }

    /** 设备属性信息 */
    @GetMapping("/device-info")
    public Map<String, Object> deviceInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dockSn", runtimeConfig.getDockSn());
        result.put("droneSn", runtimeConfig.getDroneSn());
        result.put("dockModelKey", runtimeConfig.getDockType().modelKey());
        result.put("droneModelKey", runtimeConfig.getDroneType().modelKey());
        result.put("controllerSn", runtimeConfig.getControllerSn());
        result.put("controllerModelKey", runtimeConfig.getControllerType().modelKey());
        result.put("deviceMode", runtimeConfig.getDeviceMode().name());
        return result;
    }

    // ==================== 状态查询与修改 ====================

    /** 获取设备状态 */
    @GetMapping("/state")
    public DeviceState getState() {
        return state;
    }

    /** 修改设备状态参数 */
    @PutMapping("/state")
    public Map<String, Object> updateState(@RequestBody Map<String, Object> updates) {
        if (updates.containsKey("batteryPercent")) state.setBatteryPercent(((Number) updates.get("batteryPercent")).intValue());
        if (updates.containsKey("dockTemperature")) state.setDockTemperature(((Number) updates.get("dockTemperature")).doubleValue());
        if (updates.containsKey("dockHumidity")) state.setDockHumidity(((Number) updates.get("dockHumidity")).doubleValue());
        if (updates.containsKey("windSpeed")) state.setWindSpeed(((Number) updates.get("windSpeed")).doubleValue());
        if (updates.containsKey("rainfall")) state.setRainfall(((Number) updates.get("rainfall")).intValue());
        if (updates.containsKey("coverOpen")) state.setCoverOpen(Boolean.parseBoolean(String.valueOf(updates.get("coverOpen"))));
        if (updates.containsKey("droneInDock")) state.setDroneInDock(Boolean.parseBoolean(String.valueOf(updates.get("droneInDock"))));
        if (updates.containsKey("droneActivated")) {
            boolean oldValue = state.isDroneActivated();
            boolean newValue = Boolean.parseBoolean(String.valueOf(updates.get("droneActivated")));
            // Pilot 模式下飞行器始终激活，不允许切换为休眠
            if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT && !newValue) {
                log.warn("Pilot 模式下飞行器始终激活，忽略 droneActivated=false 请求");
            } else {
                state.setDroneActivated(newValue);
                if (state.isOnline()) {
                    if (!oldValue && newValue) {
                        // 飞行器从休眠→激活：推送 drone state 初始属性（事件性上报）
                        onlineService.publishDroneState();
                    } else if (oldValue && !newValue) {
                        // 飞行器从激活→休眠：发送 update_topo 通知平台飞行器下线
                        onlineService.publishDroneSleepTopo();
                    }
                }
            }
        }
        if (updates.containsKey("droneChargeState")) state.setDroneChargeState(((Number) updates.get("droneChargeState")).intValue());
        if (updates.containsKey("backupBatteryTemperature")) state.setBackupBatteryTemperature(((Number) updates.get("backupBatteryTemperature")).doubleValue());
        if (updates.containsKey("silentMode")) state.setSilentMode(((Number) updates.get("silentMode")).intValue());
        // Pilot 模式遥控器电量（capacity_percent），Dock 模式忽略此字段
        if (updates.containsKey("controllerCapacity")) state.setControllerCapacity(((Number) updates.get("controllerCapacity")).intValue());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("state", state);
        return result;
    }

    // ==================== 机场位置 ====================

    /**
     * 获取机场位置（纬度/经度/海拔）。
     * <p>机场位置作为无人机起飞点与返航点，由用户在前端手动输入，
     * 持久化到本地配置文件，应用重启后自动恢复。</p>
     */
    @GetMapping("/location")
    public Map<String, Object> getLocation() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latitude", runtimeConfig.getLocationLatitude());
        result.put("longitude", runtimeConfig.getLocationLongitude());
        result.put("height", runtimeConfig.getLocationHeight());
        return result;
    }

    /**
     * 修改机场位置。
     * <p>遵循"业务逻辑返回明确拒绝原因而非抛异常"约定：
     * 纬度/经度/高度参数缺失或非法时返回 success=false + message，HTTP 仍为 200。</p>
     * <p>修改后自动持久化到本地配置文件，应用重启后自动恢复。</p>
     */
    @PutMapping("/location")
    public Map<String, Object> updateLocation(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object latObj = body.get("latitude");
        Object lngObj = body.get("longitude");
        Object heightObj = body.get("height");
        if (!(latObj instanceof Number) || !(lngObj instanceof Number) || !(heightObj instanceof Number)) {
            result.put("success", false);
            result.put("message", "纬度/经度/高度必须为数字");
            return result;
        }
        double latitude = ((Number) latObj).doubleValue();
        double longitude = ((Number) lngObj).doubleValue();
        double height = ((Number) heightObj).doubleValue();
        if (latitude < -90 || latitude > 90) {
            result.put("success", false);
            result.put("message", "纬度范围应为 -90 ~ 90");
            return result;
        }
        if (longitude < -180 || longitude > 180) {
            result.put("success", false);
            result.put("message", "经度范围应为 -180 ~ 180");
            return result;
        }
        runtimeConfig.setLocationLatitude(latitude);
        runtimeConfig.setLocationLongitude(longitude);
        runtimeConfig.setLocationHeight(height);
        runtimeConfig.persistLiveConfig();
        result.put("success", true);
        result.put("latitude", latitude);
        result.put("longitude", longitude);
        result.put("height", height);
        return result;
    }

    /**
     * 获取无人机实时位置。
     * <p>飞行器未激活（droneActivated=false）时位置无意义，前端应据此显示"-"。
     * 飞行中位置由 {@link WaylineTaskSimulator#updateDroneStateByStep} 按步骤更新。</p>
     */
    @GetMapping("/drone/position")
    public Map<String, Object> getDronePosition() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latitude", state.getDroneLatitude());
        result.put("longitude", state.getDroneLongitude());
        result.put("height", state.getDroneHeight());
        result.put("mode_code", state.getDroneModeCode());
        result.put("in_dock", state.isDroneInDock());
        result.put("activated", state.isDroneActivated());
        result.put("rc_lost_action", state.getRcLostAction());
        return result;
    }

    // ==================== 任务模拟 ====================

    /** 获取当前任务状态 */
    @GetMapping("/task")
    public Map<String, Object> getTaskStatus() {
        return waylineSimulator.getTaskStatus();
    }

    // ==================== 直播 ====================

    /** 获取活跃直播列表 */
    @GetMapping("/streams")
    public List<Map<String, Object>> getStreams() {
        return liveSimulator.getActiveStreams();
    }

    /**
     * 获取直播推流能力状态和限制清单。
     * <p>供前端展示当前模拟器在直播推流方面的能力限制，引导用户完成配置以获得完整功能。</p>
     */
    @GetMapping("/live/capability")
    public Map<String, Object> getLiveCapability() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("capability", ffmpegPusher.getCapability());
        result.put("mediaDir", runtimeConfig.getMediaDir());
        return result;
    }

    /**
     * 重新检测 ffmpeg WHIP 能力（用户完成安装/配置后手动触发）。
     */
    @PostMapping("/live/capability/refresh")
    public Map<String, Object> refreshLiveCapability() {
        ffmpegPusher.refresh();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("capability", ffmpegPusher.getCapability());
        return result;
    }

    /**
     * 更新直播推流配置（运行时热加载，无需重启）。
     * <p>前端修改 real-push-enabled / ffmpeg-path / video-dir 后调用此接口，
     * 自动更新 RuntimeConfig 并触发 ffmpeg 能力重新检测。</p>
     */
    @PostMapping("/live/config")
    public Map<String, Object> updateLiveConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("realPushEnabled")) {
            runtimeConfig.setLiveRealPushEnabled(Boolean.TRUE.equals(body.get("realPushEnabled")));
        }
        if (body.containsKey("ffmpegPath")) {
            String path = (String) body.get("ffmpegPath");
            runtimeConfig.setLiveFfmpegPath(path != null ? path : "");
        }
        if (body.containsKey("videoDir")) {
            String dir = (String) body.get("videoDir");
            runtimeConfig.setLiveVideoDir(dir != null ? dir : "");
        }
        if (body.containsKey("mediaDir")) {
            String dir = (String) body.get("mediaDir");
            runtimeConfig.setMediaDir(dir != null ? dir : "");
        }
        // 配置变更后自动重新检测 ffmpeg 能力
        ffmpegPusher.refresh();
        // 持久化到本地文件，确保重启后恢复
        runtimeConfig.persistLiveConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("capability", ffmpegPusher.getCapability());
        return result;
    }

    /**
     * 一键安装 FFmpeg（通过 winget）。
     * <p>执行 winget install ffmpeg，安装后自动查找路径并更新配置。
     * <p>可能触发 UAC 确认窗口，安装超时 5 分钟。
     */
    @PostMapping("/live/install-ffmpeg")
    public Map<String, Object> installFfmpeg() {
        Map<String, Object> result = ffmpegInstaller.installFfmpeg();
        // 安装成功后自动更新 ffmpegPath 并重新检测能力
        if (Boolean.TRUE.equals(result.get("success")) && result.containsKey("ffmpegPath")) {
            runtimeConfig.setLiveFfmpegPath((String) result.get("ffmpegPath"));
            runtimeConfig.setLiveRealPushEnabled(true);
            ffmpegPusher.refresh();
            runtimeConfig.persistLiveConfig();
            result.put("capability", ffmpegPusher.getCapability());
        }
        return result;
    }

    /**
     * 一键安装 FFmpeg（SSE 流式推送进度）。
     * <p>通过 SseEmitter 逐行推送 winget 安装输出，前端可实时展示下载/安装进度。
     * <p>事件类型：progress（进度行）、done（安装结果 JSON）、error（异常）。
     */
    @GetMapping(value = "/live/install-ffmpeg-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter installFfmpegStream() {
        // 超时略长于 winget 的 300s，避免 emitter 先于进程超时
        SseEmitter emitter = new SseEmitter(310_000L);
        Thread thread = new Thread(() -> {
            try {
                Map<String, Object> result = ffmpegInstaller.installFfmpeg(line -> {
                    try {
                        emitter.send(SseEmitter.event().name("progress").data(line));
                    } catch (IOException e) {
                        // 客户端已断开连接，忽略
                    }
                });
                // 安装成功后自动更新 ffmpegPath 并重新检测能力
                if (Boolean.TRUE.equals(result.get("success")) && result.containsKey("ffmpegPath")) {
                    runtimeConfig.setLiveFfmpegPath((String) result.get("ffmpegPath"));
                    runtimeConfig.setLiveRealPushEnabled(true);
                    ffmpegPusher.refresh();
                    runtimeConfig.persistLiveConfig();
                    result.put("capability", ffmpegPusher.getCapability());
                }
                emitter.send(SseEmitter.event().name("done").data(result));
                emitter.complete();
            } catch (Exception e) {
                log.error("FFmpeg 安装 SSE 异常: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage() != null ? e.getMessage() : "未知异常"));
                } catch (IOException ignored) {}
                emitter.complete();
            }
        }, "ffmpeg-installer");
        thread.setDaemon(true);
        thread.start();
        return emitter;
    }

    // ==================== 媒体 ====================

    /** 获取已上传媒体文件列表 */
    @GetMapping("/media")
    public List<Map<String, Object>> getMedia() {
        return mediaSimulator.getUploadedFiles();
    }

    /**
     * 手动触发媒体上传流程。
     * <p>请求体示例：{@code {"flight_id":"FLIGHT-001","file_count":3}}。
     * file_count 未传时默认 3。</p>
     * <p>异步执行（媒体上传涉及等待 events_reply，耗时较长），立即返回触发结果，
     * 上传进度通过 GET /api/media 查询已上报文件列表。</p>
     * <p>遵循"业务逻辑返回明确拒绝原因而非抛异常"约定：
     * MQTT 未连接、flight_id 为空等返回 success=false + message，HTTP 仍为 200。</p>
     */
    @PostMapping("/media/trigger")
    public Map<String, Object> triggerMediaUpload(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!mqtt.isConnected()) {
            result.put("success", false);
            result.put("message", "MQTT 未连接，无法触发媒体上传");
            return result;
        }
        String flightId = String.valueOf(body.getOrDefault("flight_id", ""));
        if (flightId.isBlank() || "null".equals(flightId)) {
            result.put("success", false);
            result.put("message", "flight_id 不能为空");
            return result;
        }
        int fileCount = body.get("file_count") != null
                ? ((Number) body.get("file_count")).intValue() : 3;

        // 异步执行媒体上传流程（避免阻塞 HTTP 请求）
        java.util.concurrent.CompletableFuture.runAsync(
                () -> mediaSimulator.simulateMediaUpload(flightId, fileCount));

        result.put("success", true);
        result.put("message", "媒体上传已异步触发");
        result.put("flight_id", flightId);
        result.put("file_count", fileCount);
        return result;
    }

    // ==================== HMS 异常模拟 ====================

    /**
     * 触发一次 HMS 健康告警上报。
     * <p>请求体示例：{@code {"types": ["wind_high", "battery_low"]}}。
     * 类型名称与 {@link ltd.cdmi.hivemind.simulator.handler.HmsSimulator.AlarmType} 枚举常量一致（大小写不敏感）。</p>
     * <p>遵循"业务逻辑返回明确拒绝原因而非抛异常"约定：
     * 未选择类型、MQTT 未连接、类型无效等均返回 success=false + 明确 message，HTTP 仍为 200。</p>
     */
    @PostMapping("/hms/trigger")
    public Map<String, Object> triggerHms(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) body.get("types");

        HmsSimulator.TriggerResult r = hmsSimulator.trigger(types);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        result.put("code", r.code());
        result.put("message", r.message());
        result.put("count", r.count());
        return result;
    }

    // ==================== 指令飞行事件触发（drc.html，无前端 UI） ====================

    /**
     * 触发 obstacle_avoidance_notify 事件（仅 Dock3）。
     * <p>请求体示例：
     * {@code {"wayline_uuid":"xxx","flight_id":"yyy","is_final_report":true,
     *   "obstacles":[{"id":"o1","type":0,"timestamp":1700000000000,"latitude":30.67,"longitude":104.07,"height":100,"wayline_id":"w1","waypoint_index":0}]}}</p>
     */
    @PostMapping("/flight/obstacle-avoidance-notify")
    public Map<String, Object> triggerObstacleAvoidanceNotify(@RequestBody Map<String, Object> body) {
        String waylineUuid = String.valueOf(body.getOrDefault("wayline_uuid", ""));
        String flightId = String.valueOf(body.getOrDefault("flight_id", ""));
        boolean isFinalReport = Boolean.parseBoolean(String.valueOf(body.getOrDefault("is_final_report", false)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> obstacles = (List<Map<String, Object>>) body.getOrDefault("obstacles", List.of());

        String err = flightCommandSimulator.triggerObstacleAvoidanceNotify(waylineUuid, flightId, obstacles, isFinalReport);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", err == null);
        if (err != null) {
            result.put("message", err);
        }
        return result;
    }

    /**
     * 触发 joystick_invalid_notify 事件（三 Dock 共有）。
     * <p>请求体示例：{@code {"reason":0}}</p>
     * <p>reason 枚举：0=遥控器失联, 1=低电量返航, 2=低电量降落, 3=靠近限飞区, 4=遥控器夺权</p>
     */
    @PostMapping("/flight/joystick-invalid-notify")
    public Map<String, Object> triggerJoystickInvalidNotify(@RequestBody Map<String, Object> body) {
        int reason = ((Number) body.getOrDefault("reason", 0)).intValue();
        String err = flightCommandSimulator.triggerJoystickInvalidNotify(reason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", err == null);
        if (err != null) {
            result.put("message", err);
        }
        return result;
    }

    /**
     * 触发 camera_photo_take_progress 事件（三 Dock 共有）。
     * <p>请求体示例：{@code {"status":"in_progress","current_step":3002,"percent":50,"camera_mode":3}}</p>
     * <p>status 枚举：fail/in_progress/ok；current_step 枚举：3000/3002/3005</p>
     */
    @PostMapping("/flight/camera-photo-take-progress")
    public Map<String, Object> triggerCameraPhotoTakeProgress(@RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.getOrDefault("status", "in_progress"));
        int currentStep = ((Number) body.getOrDefault("current_step", 3000)).intValue();
        int percent = ((Number) body.getOrDefault("percent", 0)).intValue();
        int cameraMode = ((Number) body.getOrDefault("camera_mode", 3)).intValue();
        String err = flightCommandSimulator.triggerCameraPhotoTakeProgress(status, currentStep, percent, cameraMode);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", err == null);
        if (err != null) {
            result.put("message", err);
        }
        return result;
    }

    /**
     * 触发 poi_status_notify 事件（仅 Dock1）。
     * <p>请求体示例：{@code {"status":"in_progress","reason":0,"circle_radius":50.0,"circle_speed":5.0,"max_circle_speed":15.0}}</p>
     */
    @PostMapping("/flight/poi-status-notify")
    public Map<String, Object> triggerPoiStatusNotify(@RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.getOrDefault("status", "in_progress"));
        int reason = ((Number) body.getOrDefault("reason", 0)).intValue();
        double circleRadius = ((Number) body.getOrDefault("circle_radius", 0)).doubleValue();
        double circleSpeed = ((Number) body.getOrDefault("circle_speed", 0)).doubleValue();
        double maxCircleSpeed = ((Number) body.getOrDefault("max_circle_speed", 0)).doubleValue();
        String err = flightCommandSimulator.triggerPoiStatusNotify(status, reason, circleRadius, circleSpeed, maxCircleSpeed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", err == null);
        if (err != null) {
            result.put("message", err);
        }
        return result;
    }

    @PostMapping("/flight/trigger-rc-lost")
    public Map<String, Object> triggerRcLost() {
        String err = flightCommandSimulator.triggerRcLost();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", err == null);
        if (err != null) {
            result.put("message", err);
        }
        return result;
    }

    /** 设置 rc_lost_action 值（0=悬停, 1=降落, 2=返航） */
    @PostMapping("/drone/rc-lost-action")
    public Map<String, Object> setRcLostAction(@RequestBody Map<String, Object> body) {
        Object value = body.get("rc_lost_action");
        int action;
        try {
            action = Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "rc_lost_action 值无效");
            return result;
        }
        if (action < 0 || action > 2) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "rc_lost_action 取值范围 0-2");
            return result;
        }
        state.setRcLostAction(action);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("rc_lost_action", action);
        return result;
    }

    // ==================== 消息日志 ====================

    /** 获取最近 MQTT 消息日志 */
    @GetMapping("/logs")
    public List<Map<String, Object>> getLogs() {
        return mqtt.getLogs();
    }

    /** 清空消息日志 */
    @DeleteMapping("/logs")
    public Map<String, Object> clearLogs() {
        mqtt.clearLogs();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    // ==================== 连接状态与配置 ====================

    /** 获取 MQTT 连接状态 */
    @GetMapping("/connection")
    public Map<String, Object> getConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mqtt_connected", mqtt.isConnected());
        result.put("online", state.isOnline());
        return result;
    }

    /**
     * 获取当前连接配置（密码脱敏）。
     */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mqtt_host", runtimeConfig.getMqttHost());
        result.put("mqtt_port", runtimeConfig.getMqttPort());
        result.put("mqtt_username", runtimeConfig.getMqttUsername());
        result.put("mqtt_password", "");  // 密码不回传
        result.put("organization_id", runtimeConfig.getOrganizationId());
        result.put("device_binding_code", runtimeConfig.getDeviceBindingCode());
        result.put("app_license", runtimeConfig.getAppLicense());
        result.put("dock_type", runtimeConfig.getDockType().name());
        result.put("drone_type", runtimeConfig.getDroneType().name());
        result.put("device_mode", runtimeConfig.getDeviceMode().name());
        result.put("controller_type", runtimeConfig.getControllerType().name());
        result.put("controller_sn", runtimeConfig.getControllerSn());
        result.put("dock_sn", runtimeConfig.getDockSn());
        result.put("drone_sn", runtimeConfig.getDroneSn());
        return result;
    }

    /**
     * 更新连接配置并重连第三方平台。
     * <p>支持部分更新：仅更新请求中包含的字段；密码为空时保留原值。</p>
     */
    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestBody Map<String, Object> config) {
        // 保存原始密码，连接失败时恢复（避免错误密码覆盖原密码，导致清空密码时无法使用原密码）
        String originalPassword = runtimeConfig.getMqttPassword();

        // 更新配置（仅更新非空字段）
        if (config.containsKey("mqtt_host") && !String.valueOf(config.get("mqtt_host")).isBlank()) {
            runtimeConfig.setMqttHost(String.valueOf(config.get("mqtt_host")).trim());
        }
        if (config.containsKey("mqtt_port") && config.get("mqtt_port") != null) {
            try {
                runtimeConfig.setMqttPort(((Number) config.get("mqtt_port")).intValue());
            } catch (ClassCastException ignored) {
                runtimeConfig.setMqttPort(Integer.parseInt(String.valueOf(config.get("mqtt_port"))));
            }
        }
        if (config.containsKey("mqtt_username") && !String.valueOf(config.get("mqtt_username")).isBlank()) {
            runtimeConfig.setMqttUsername(String.valueOf(config.get("mqtt_username")).trim());
        }
        if (config.containsKey("mqtt_password") && !String.valueOf(config.get("mqtt_password")).isBlank()) {
            runtimeConfig.setMqttPassword(String.valueOf(config.get("mqtt_password")));
        }
        if (config.containsKey("organization_id") && !String.valueOf(config.get("organization_id")).isBlank()) {
            runtimeConfig.setOrganizationId(String.valueOf(config.get("organization_id")).trim());
        }
        if (config.containsKey("device_binding_code") && !String.valueOf(config.get("device_binding_code")).isBlank()) {
            runtimeConfig.setDeviceBindingCode(String.valueOf(config.get("device_binding_code")).trim());
        }
        if (config.containsKey("app_license") && !String.valueOf(config.get("app_license")).isBlank()) {
            runtimeConfig.setAppLicense(String.valueOf(config.get("app_license")).trim());
        }
        if (config.containsKey("dock_type") && config.get("dock_type") != null) {
            try {
                runtimeConfig.setDockType(DeviceType.valueOf(String.valueOf(config.get("dock_type")).trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (config.containsKey("drone_type") && config.get("drone_type") != null) {
            try {
                runtimeConfig.setDroneType(DeviceType.valueOf(String.valueOf(config.get("drone_type")).trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (config.containsKey("device_mode") && config.get("device_mode") != null) {
            try {
                runtimeConfig.setDeviceMode(DeviceMode.valueOf(String.valueOf(config.get("device_mode")).trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (config.containsKey("controller_type") && config.get("controller_type") != null) {
            try {
                runtimeConfig.setControllerType(DeviceType.valueOf(String.valueOf(config.get("controller_type")).trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }

        // 重连前若设备在线，先标记离线（重连后需重新上线）
        if (state.isOnline()) {
            state.setOnline(false);
        }

        DiagnosticCode connectCode = mqtt.reconnect();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mqtt_connected", mqtt.isConnected());
        result.put("online", state.isOnline());
        result.put("broker", runtimeConfig.getMqttHost() + ":" + runtimeConfig.getMqttPort());

        if (connectCode != null) {
            // 连接失败，恢复密码（避免错误密码覆盖原密码）
            runtimeConfig.setMqttPassword(originalPassword);
            result.put("success", false);
            result.put("code", connectCode.code());
            return result;
        }

        result.put("success", true);
        result.put("code", "0");
        result.put("online", state.isOnline());
        return result;
    }

    // ==================== 诊断日志 ====================

    /** 获取 S/P/M 诊断日志列表 */
    @GetMapping("/diagnostic/logs")
    public Map<String, Object> getDiagnosticLogs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("logs", diagnosticRecorder.getLogs());
        return result;
    }

    /** 清空诊断日志 */
    @DeleteMapping("/diagnostic/logs")
    public Map<String, Object> clearDiagnosticLogs() {
        diagnosticRecorder.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    // ==================== 覆盖率报告 ====================

    /**
     * 获取所有已采集过覆盖率的 MQTT 地址列表及当前模拟器 MQTT 地址。
     * <p>用于前端展示地址下拉框，用户选择后下载对应报告。</p>
     */
    @GetMapping("/coverage/hosts")
    public Map<String, Object> coverageHosts() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("current_host", runtimeConfig.getMqttHost() + ":" + runtimeConfig.getMqttPort());
        result.put("hosts", coverageRecorder.getHosts());
        return result;
    }

    /**
     * 获取指定 MQTT 地址的覆盖率数据（JSON）。
     * host 参数缺失时使用当前模拟器 MQTT 地址。
     */
    @GetMapping("/coverage/data")
    public Map<String, Object> coverageData(@RequestParam(required = false) String host) {
        String target = (host == null || host.isBlank())
                ? runtimeConfig.getMqttHost() + ":" + runtimeConfig.getMqttPort()
                : host;
        return coverageRecorder.getCoverage(target);
    }

    /**
     * 下载 HTML 覆盖率报告。
     * <p>返回 text/html 内容，浏览器直接渲染或另存为文件。
     * host 参数缺失时使用当前模拟器 MQTT 地址。</p>
     */
    @GetMapping(value = "/coverage/report", produces = "text/html; charset=UTF-8")
    public String coverageReport(@RequestParam(required = false) String host) {
        String target = (host == null || host.isBlank())
                ? runtimeConfig.getMqttHost() + ":" + runtimeConfig.getMqttPort()
                : host;
        return coverageRecorder.generateHtmlReport(target);
    }
}
