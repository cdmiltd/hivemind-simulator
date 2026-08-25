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
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.hivemind.simulator.device.PilotOnlineService;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.media.FfmpegWhipPusher;
import ltd.cdmi.hivemind.simulator.media.FfmpegInstaller;
import ltd.cdmi.hivemind.simulator.media.LocalFileUploadService;
import ltd.cdmi.hivemind.simulator.media.MediaSampleInitializer;
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.handler.AirSenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator;
import ltd.cdmi.hivemind.simulator.handler.PsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.EsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.RemoteLogSimulator;
import ltd.cdmi.hivemind.simulator.handler.OtaSimulator;
import ltd.cdmi.hivemind.simulator.handler.UnlockLicenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.HmsSimulator;
import ltd.cdmi.hivemind.simulator.handler.LiveStreamSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.handler.MapElementSimulator;
import ltd.cdmi.hivemind.simulator.handler.SituationAwarenessSimulator;
import ltd.cdmi.hivemind.simulator.handler.PilotHttpSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.ws.MopClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模拟器 REST API，供 Web 控制台调用。
 *
 * <h3>关键约束（详见 AGENTS.md §2.2 注册流程、§2.3 工程约束）</h3>
 * <ul>
 *   <li><b>注册时序</b>：{@code config → airport_bind_status → airport_organization_get → airport_organization_bind}，
 *       注册成功后触发 update_topo 上线。每一步指令<b>无条件执行</b>，不根据绑定状态跳过。</li>
 *   <li><b>注册失败处理</b>：绑定码错误（{@code result=210229}）停止注册，提示「组织ID与绑定码错误」；
 *       config 请求超时重试 3 次（间隔 3 秒），全失败才停止注册；
 *       config 回复的 {@code app_license} 需与本地配置比对一致，不一致停止注册返回 {@code -6}；
 *       本地未配置（留空）时跳过校验，不模拟 License 认证。</li>
 *   <li><b>update_topo 行为</b>：对齐 DJI 真机——超时不停止上线流程。</li>
 *   <li><b>关机流程</b>（{@link #offline()}）：停止 OSD + 停止直播推流 + update_topo 空列表 +
 *       断开 MQTT + 清空诊断日志与 MQTT 消息日志（会话结束，避免下次开机翻页加载跨会话历史消息）。</li>
 *   <li><b>业务返回</b>：业务逻辑返回明确拒绝原因而非抛异常（HTTP 200 + {@code success=false} + {@code message}）。
 *       不适用值用 {@code "-"} 显示，不用 {@code 0} 或 {@code "--"}。</li>
 *   <li><b>电源状态</b>：由后端 {@link DeviceState} 持久化，刷新页面不丢失；{@code /api/offline} 重置为关机，
 *       {@code /api/disconnect} 仅断开 MQTT 不重置电源。</li>
 * </ul>
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
    private final AirSenseSimulator airSenseSimulator;
    private final FlightAreaSimulator flightAreaSimulator;
    private final UnlockLicenseSimulator unlockLicenseSimulator;
    private final PsdkSimulator psdkSimulator;
    private final EsdkSimulator esdkSimulator;
    private final RemoteLogSimulator remoteLogSimulator;
    private final OtaSimulator otaSimulator;
    private final FlightCommandSimulator flightCommandSimulator;
    private final FfmpegWhipPusher ffmpegPusher;
    private final FfmpegInstaller ffmpegInstaller;
    private final LocalFileUploadService localFileUploadService;
    private final MediaSampleInitializer mediaSampleInitializer;
    private final RuntimeConfig runtimeConfig;
    private final SimulatorProperties props;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final CoverageRecorder coverageRecorder;
    private final ObjectMapper objectMapper;
    private final MapElementSimulator mapElementSimulator;
    private final SituationAwarenessSimulator situationAwarenessSimulator;
    private final PilotHttpSimulator pilotHttpSimulator;
    private final MopClient mopClient;

    /** 应用版本号：从 Jar MANIFEST 读取（Spring Boot Maven Plugin 自动写入 Implementation-Version），
     * IDE 开发模式下为 null，回退为 "dev"。 */
    private final String appVersion;

    public SimulatorController(DockOnlineService onlineService, PilotOnlineService pilotOnlineService,
                               DeviceState state,
                               MqttClientManager mqtt, WaylineTaskSimulator waylineSimulator,
                               LiveStreamSimulator liveSimulator, MediaUploadSimulator mediaSimulator,
                               HmsSimulator hmsSimulator, AirSenseSimulator airSenseSimulator,
                               FlightAreaSimulator flightAreaSimulator,
                               UnlockLicenseSimulator unlockLicenseSimulator,
                               PsdkSimulator psdkSimulator,
                               EsdkSimulator esdkSimulator,
                               RemoteLogSimulator remoteLogSimulator,
                               OtaSimulator otaSimulator,
                               FlightCommandSimulator flightCommandSimulator,
                               FfmpegWhipPusher ffmpegPusher, FfmpegInstaller ffmpegInstaller,
                               LocalFileUploadService localFileUploadService,
                               MediaSampleInitializer mediaSampleInitializer,
                               RuntimeConfig runtimeConfig, SimulatorProperties props,
                               DiagnosticLogRecorder diagnosticRecorder,
                               CoverageRecorder coverageRecorder,
                               ObjectMapper objectMapper,
                               MapElementSimulator mapElementSimulator,
                               SituationAwarenessSimulator situationAwarenessSimulator,
                               PilotHttpSimulator pilotHttpSimulator,
                               MopClient mopClient) {
        this.onlineService = onlineService;
        this.pilotOnlineService = pilotOnlineService;
        this.state = state;
        this.mqtt = mqtt;
        this.waylineSimulator = waylineSimulator;
        this.liveSimulator = liveSimulator;
        this.mediaSimulator = mediaSimulator;
        this.hmsSimulator = hmsSimulator;
        this.airSenseSimulator = airSenseSimulator;
        this.flightAreaSimulator = flightAreaSimulator;
        this.unlockLicenseSimulator = unlockLicenseSimulator;
        this.psdkSimulator = psdkSimulator;
        this.esdkSimulator = esdkSimulator;
        this.remoteLogSimulator = remoteLogSimulator;
        this.otaSimulator = otaSimulator;
        this.flightCommandSimulator = flightCommandSimulator;
        this.ffmpegPusher = ffmpegPusher;
        this.ffmpegInstaller = ffmpegInstaller;
        this.localFileUploadService = localFileUploadService;
        this.mediaSampleInitializer = mediaSampleInitializer;
        this.runtimeConfig = runtimeConfig;
        this.props = props;
        this.diagnosticRecorder = diagnosticRecorder;
        this.coverageRecorder = coverageRecorder;
        this.objectMapper = objectMapper;
        this.mapElementSimulator = mapElementSimulator;
        this.situationAwarenessSimulator = situationAwarenessSimulator;
        this.pilotHttpSimulator = pilotHttpSimulator;
        this.mopClient = mopClient;
        // 读取 Jar 版本号：IDE 开发模式下 Implementation-Version 为 null，回退为 "dev"
        String implVer = getClass().getPackage().getImplementationVersion();
        this.appVersion = (implVer != null && !implVer.isBlank()) ? implVer : "dev";
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
        result.put("step", onlineResult.step());
        result.put("online", state.isOnline());
        result.put("mqtt_connected", mqtt.isConnected());
        return result;
    }

    /** 设备关机：停止 OSD + 停止直播推流 + update_topo 空列表 + 断开 MQTT + 清空诊断日志与 MQTT 消息日志（会话结束，避免下次开机翻页加载跨会话历史消息） */
    @PostMapping("/offline")
    public Map<String, Object> offline() {
        if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
            pilotOnlineService.offline();
        } else {
            onlineService.offline();
        }
        // 停止全部直播推流（含 ffmpeg 进程），防止下线后推流泄漏（TC-LIVE-021）
        liveSimulator.stopAllStreams();
        mqtt.disconnect();
        diagnosticRecorder.clear();
        mqtt.clearLogs();
        // 关机 = 电源关闭（断开 MQTT ≠ 关机，/api/disconnect 不复位电源状态）
        state.setPowered(false);
        // 无人机关机恢复入舱状态（休眠+在舱+待机+位置归位，对齐 DeviceSimulator @PostConstruct 初始化逻辑）
        state.setDroneActivated(false);  // 休眠（停止推送 drone OSD）
        state.setDroneInDock(true);       // 在舱
        state.setDroneModeCode(0);        // 待机
        state.setDroneLatitude(runtimeConfig.getLocationLatitude());   // 机场纬度
        state.setDroneLongitude(runtimeConfig.getLocationLongitude()); // 机场经度
        state.setDroneHeight(0.0);        // 相对起飞点高度归零
        state.setDroneElevation(runtimeConfig.getLocationHeight());     // 海拔=机场海拔
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("online", state.isOnline());
        result.put("mqtt_connected", mqtt.isConnected());
        return result;
    }

    /** 断开 MQTT 连接（不执行下线流程，用于注册失败等场景，保留诊断日志供查看失败详情，TC-REG-024） */
    @PostMapping("/disconnect")
    public Map<String, Object> disconnect() {
        mqtt.disconnect();
        Map<String, Object> disconnectResult = new LinkedHashMap<>();
        disconnectResult.put("success", true);
        disconnectResult.put("mqtt_connected", mqtt.isConnected());
        return disconnectResult;
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
        result.put("thingVersion", runtimeConfig.getThingVersion());
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
     * <p>联动无人机位置（TC-LOC-017/018）：无人机在舱（droneInDock=true）时同步更新
     * droneLatitude/droneLongitude/droneElevation 为新机场位置、droneHeight=0；
     * 飞行中（droneInDock=false）则保持飞行模拟器控制的位置，不干预。</p>
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
        // 无人机在舱时同步更新位置为新机场位置；飞行中保持飞行模拟器控制的位置（TC-LOC-017/018）
        if (state.isDroneInDock()) {
            state.setDroneLatitude(latitude);
            state.setDroneLongitude(longitude);
            state.setDroneElevation(height);
            state.setDroneHeight(0.0);
        }
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
     * <p>drc_state 为 DRC 链路状态（TC-DRC-057），供模拟器页面飞行器状态栏显示。</p>
     */
    @GetMapping("/drone/position")
    public Map<String, Object> getDronePosition() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latitude", state.getDroneLatitude());
        result.put("longitude", state.getDroneLongitude());
        result.put("height", state.getDroneHeight());
        result.put("elevation", state.getDroneElevation());
        result.put("mode_code", state.getDroneModeCode());
        result.put("in_dock", state.isDroneInDock());
        result.put("activated", state.isDroneActivated());
        result.put("rc_lost_action", state.getRcLostAction());
        result.put("drc_state", state.getDrcState());
        return result;
    }

    // ==================== 任务模拟 ====================

    /** 获取当前任务状态 */
    @GetMapping("/task")
    public Map<String, Object> getTaskStatus() {
        return waylineSimulator.getTaskStatus();
    }

    /**
     * 手动触发 flighttask_ready 事件（任务就绪通知）。
     * <p>请求体：{"flight_ids": ["task-id-1", "task-id-2"]}</p>
     */
    @PostMapping("/flighttask-ready")
    public Map<String, Object> publishFlighttaskReady(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> flightIds = (List<String>) body.getOrDefault("flight_ids", List.of());
        waylineSimulator.publishFlighttaskReady(flightIds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("flight_ids", flightIds);
        return result;
    }

    /**
     * 手动触发 device_exit_homing_notify 事件（设备返航退出状态通知）。
     * <p>请求体：{"action": 1, "reason": 0}</p>
     */
    @PostMapping("/device-exit-homing-notify")
    public Map<String, Object> publishDeviceExitHomingNotify(@RequestBody Map<String, Object> body) {
        int action = ((Number) body.getOrDefault("action", 1)).intValue();
        int reason = ((Number) body.getOrDefault("reason", 0)).intValue();
        waylineSimulator.publishDeviceExitHomingNotify(action, reason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("action", action);
        result.put("reason", reason);
        return result;
    }

    /**
     * 手动触发 flight_setup_exception_notify 事件（机场任务准备异常通知，仅 Dock1）。
     * <p>请求体：{"flight_id": "可选，为空则用当前任务", "timeout_time": 6, "flight_type": 1}</p>
     * <p>timeout_time：异常超时时间（分钟，合法值 2/4/6/8/10）；flight_type：1=航线任务，2=指令飞行任务</p>
     */
    @PostMapping("/flight-setup-exception-notify")
    public Map<String, Object> publishFlightSetupExceptionNotify(@RequestBody Map<String, Object> body) {
        String flightId = body.get("flight_id") == null ? null : String.valueOf(body.get("flight_id"));
        int timeoutTime = ((Number) body.getOrDefault("timeout_time", 6)).intValue();
        int flightType = ((Number) body.getOrDefault("flight_type", 1)).intValue();
        boolean sent = waylineSimulator.publishFlightSetupExceptionNotify(flightId, timeoutTime, flightType);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", sent);
        if (!sent) {
            result.put("message", "当前 Dock 类型不支持 flight_setup_exception_notify（仅 Dock1 支持）");
        }
        result.put("timeout_time", timeoutTime);
        result.put("flight_type", flightType);
        return result;
    }

    /**
     * 手动触发 in_flight_wayline_progress 事件（空中下发航线状态上报）。
     * <p>请求体：{"in_flight_wayline_id": "xxx", "percent": 50, "status": 3, "result": 0, "way_point_index": 2}</p>
     */
    @PostMapping("/in-flight-wayline-progress")
    public Map<String, Object> publishInFlightWaylineProgress(@RequestBody Map<String, Object> body) {
        String waylineId = (String) body.getOrDefault("in_flight_wayline_id", "");
        int percent = ((Number) body.getOrDefault("percent", 0)).intValue();
        int status = ((Number) body.getOrDefault("status", 0)).intValue();
        int result_code = ((Number) body.getOrDefault("result", 0)).intValue();
        int wayPointIndex = ((Number) body.getOrDefault("way_point_index", 0)).intValue();
        waylineSimulator.publishInFlightWaylineProgress(waylineId, percent, status, result_code, wayPointIndex);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 发送 flighttask_progress_get 请求（蛙跳任务中查询另一机场的任务状态）。
     * <p>请求体：{"sn": "目标设备SN", "flight_id": "航线任务ID（Dock2 必填）"}
     * <p>按当前 Dock 类型发送对应字段：Dock2 → target_sn + flight_id，Dock3 → sn。</p>
     * <p>回复：平台的 requests_reply，超时返回 success=false</p>
     */
    @PostMapping("/flighttask-progress-get")
    public Map<String, Object> publishFlighttaskProgressGet(@RequestBody Map<String, Object> body) {
        String sn = (String) body.getOrDefault("sn", "");
        String flightId = (String) body.getOrDefault("flight_id", "");
        JsonNode reply = waylineSimulator.publishFlighttaskProgressGet(sn, flightId);
        if (reply == null) {
            return Map.of("success", false, "message", "平台未回复（超时）");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("reply", reply);
        return result;
    }

    /**
     * 发送 flighttask_resource_get 请求（获取任务航线文件资源）。
     * <p>请求体：{"flight_id": "任务ID"}</p>
     * <p>回复：平台的 requests_reply，超时返回 success=false</p>
     */
    @PostMapping("/flighttask-resource-get")
    public Map<String, Object> publishFlighttaskResourceGet(@RequestBody Map<String, Object> body) {
        String flightId = (String) body.getOrDefault("flight_id", "");
        JsonNode reply = waylineSimulator.publishFlighttaskResourceGet(flightId);
        if (reply == null) {
            return Map.of("success", false, "message", "平台未回复（超时）");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("reply", reply);
        return result;
    }

    /**
     * 手动触发 failed 状态的 flighttask_progress，携带 break_reason。
     * <p>请求体：{"break_reason": 528}（可选，未传则按当前 Dock 型号默认值：dock1=528/dock2=529/dock3=517）</p>
     * <p>break_reason 按当前 Dock 型号校验，非法值拒绝发送（529 仅 dock2 支持）</p>
     */
    @PostMapping("/flighttask-progress-break")
    public Map<String, Object> publishFlighttaskProgressBreak(@RequestBody Map<String, Object> body) {
        boolean sent;
        if (body.containsKey("break_reason")) {
            int breakReason = ((Number) body.get("break_reason")).intValue();
            sent = waylineSimulator.publishProgressFailedWithBreakReason(breakReason);
        } else {
            sent = waylineSimulator.publishProgressFailedWithBreakReason();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", sent);
        if (!sent) {
            result.put("message", "break_reason 在当前 Dock 类型中非法，参考 DJI wayline.html 枚举");
        }
        return result;
    }

    // ==================== 直播 ====================

    /** 获取活跃直播列表 */
    @GetMapping("/streams")
    public List<Map<String, Object>> getStreams() {
        return liveSimulator.getActiveStreams();
    }

    /**
     * 获取推流记录列表（TC-LIVE-023~025，供直播推流面板排错）。
     * <p>含失败记录（513013 场景）与容错/降级场景的实际推流地址，
     * 供用户核对平台下发的推流地址是否配错。</p>
     */
    @GetMapping("/live/push-records")
    public List<Map<String, Object>> getPushRecords() {
        return liveSimulator.getPushRecords();
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
        // media-dir 首次配置时预置示例照片/视频（落地即触发上传，无运行时补传机会，TC-MEDIA-016）
        mediaSampleInitializer.ensurePresetMediaFiles();
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

    /**
     * 上传直播推流视频文件到 video-dir 目录（multipart，字段名 file）。
     * <p>Docker 部署场景：video-dir 为 named volume，宿主机文件无法直接放入，
     * 通过 Web UI 上传替代 docker cp。文件名保留原名（default.mp4 或
     * {camera_index}-{video_type}.mp4），与推流查找规则兼容。
     */
    @PostMapping("/live/videos/upload")
    public Map<String, Object> uploadLiveVideo(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = localFileUploadService.uploadLiveVideo(file);
        // 成功时携带最新能力信息（videosFound 已包含新上传文件），前端免二次请求
        if (Boolean.TRUE.equals(result.get("success"))) {
            result.put("capability", ffmpegPusher.getCapability());
        }
        return result;
    }

    // ==================== 媒体 ====================

    /** 获取已上传媒体文件列表 */
    @GetMapping("/media")
    public List<Map<String, Object>> getMedia() {
        return mediaSimulator.getUploadedFiles();
    }

    /**
     * 上传媒体素材文件到 media-dir 目录（multipart，字段名 file）。
     * <p>本地文件管理（无需 MQTT），文件进入媒体上传素材池，任务完成触发媒体上传时循环取用。
     * 与 POST /api/media/trigger（协议流程触发，需 MQTT）职责分明。
     * <p>Docker 部署场景：media-dir 为 named volume，通过 Web UI 上传替代 docker cp。
     */
    @PostMapping("/media/files/upload")
    public Map<String, Object> uploadMediaFile(@RequestParam("file") MultipartFile file) {
        return localFileUploadService.uploadMedia(file);
    }

    /** 获取 media-dir 内媒体素材文件名列表（配置弹窗展示目录内容）。 */
    @GetMapping("/media/files")
    public Map<String, Object> listMediaFiles() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mediaFiles", localFileUploadService.listMediaFileNames());
        return result;
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

    /**
     * 触发 AirSense 告警通知（method=airsense_warning）。
     * <p>请求体示例：
     * {@code {"icao":"B-5931","warning_level":3,"latitude":12.23,"longitude":12.23,
     *   "altitude":100,"altitude_type":1,"heading":89.1,
     *   "relative_altitude":80,"vert_trend":0,"distance":100}}</p>
     * <p>遵循"业务逻辑返回明确拒绝原因而非抛异常"约定。</p>
     */
    @PostMapping("/airsense-warning")
    public Map<String, Object> triggerAirSenseWarning(@RequestBody Map<String, Object> body) {
        String icao = (String) body.getOrDefault("icao", "B-0001");
        int warningLevel = ((Number) body.getOrDefault("warning_level", 3)).intValue();
        double latitude = ((Number) body.getOrDefault("latitude", 0.0)).doubleValue();
        double longitude = ((Number) body.getOrDefault("longitude", 0.0)).doubleValue();
        int altitude = ((Number) body.getOrDefault("altitude", 100)).intValue();
        int altitudeType = ((Number) body.getOrDefault("altitude_type", 1)).intValue();
        double heading = ((Number) body.getOrDefault("heading", 0.0)).doubleValue();
        int relativeAltitude = ((Number) body.getOrDefault("relative_altitude", 50)).intValue();
        int vertTrend = ((Number) body.getOrDefault("vert_trend", 0)).intValue();
        int distance = ((Number) body.getOrDefault("distance", 100)).intValue();

        AirSenseSimulator.AirSenseAlert alert = new AirSenseSimulator.AirSenseAlert(
                icao, warningLevel, latitude, longitude, altitude,
                altitudeType, heading, relativeAltitude, vertTrend, distance);

        AirSenseSimulator.TriggerResult r = airSenseSimulator.trigger(List.of(alert));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        result.put("code", r.code());
        result.put("message", r.message());
        result.put("count", r.count());
        return result;
    }

    // ==================== 自定义飞行区触发（wayline.html，Dock3） ====================

    /**
     * 触发飞行器位置告警推送（method=flight_areas_drone_location）。
     * <p>请求体示例：
     * {@code {"locations":[{"area_id":"xxx","area_distance":100.11,"is_in_area":true}]}}</p>
     * <p>遵循"业务逻辑返回明确拒绝原因而非抛异常"约定。</p>
     */
    @PostMapping("/flight-areas/drone-location")
    public Map<String, Object> triggerFlightAreaDroneLocation(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawLocations = (List<Map<String, Object>>) body.getOrDefault("locations", List.of());

        List<FlightAreaSimulator.DroneLocation> locations = rawLocations.stream()
                .map(loc -> new FlightAreaSimulator.DroneLocation(
                        (String) loc.getOrDefault("area_id", ""),
                        ((Number) loc.getOrDefault("area_distance", 0.0)).doubleValue(),
                        Boolean.TRUE.equals(loc.get("is_in_area"))))
                .toList();

        FlightAreaSimulator.TriggerResult r = flightAreaSimulator.triggerDroneLocation(locations);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        result.put("code", r.code());
        result.put("message", r.message());
        result.put("count", r.count());
        return result;
    }

    /**
     * 触发文件同步进度上报（method=flight_areas_sync_progress）。
     * <p>请求体示例：
     * {@code {"status":"synchronized","reason":0,"file":{"name":"geofence_xxx.json","checksum":"sha256"}}}</p>
     * <p>status 枚举：fail/switch_fail/synchronized/synchronizing/wait_sync</p>
     */
    @PostMapping("/flight-areas/sync-progress")
    public Map<String, Object> triggerFlightAreaSyncProgress(@RequestBody Map<String, Object> body) {
        String statusStr = (String) body.getOrDefault("status", "synchronized");
        FlightAreaSimulator.SyncStatus status = FlightAreaSimulator.SyncStatus.fromCode(statusStr);
        int reason = ((Number) body.getOrDefault("reason", 0)).intValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> fileMap = (Map<String, Object>) body.get("file");
        FlightAreaSimulator.FlightAreaFile file = null;
        if (fileMap != null) {
            file = new FlightAreaSimulator.FlightAreaFile(
                    (String) fileMap.getOrDefault("name", ""),
                    (String) fileMap.getOrDefault("checksum", ""));
        }

        FlightAreaSimulator.TriggerResult r = flightAreaSimulator.triggerSyncProgress(status, reason, file);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        result.put("code", r.code());
        result.put("message", r.message());
        return result;
    }

    /**
     * 主动获取飞行区文件（method=flight_areas_get，Requests）。
     * <p>发送 requests 并等待平台回复，返回 reply 内容。</p>
     */
    @PostMapping("/flight-areas/get")
    public Map<String, Object> requestFlightAreas() {
        FlightAreaSimulator.RequestResult r = flightAreaSimulator.requestFlightAreas();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        result.put("code", r.code());
        result.put("message", r.message());
        if (r.reply() != null) {
            result.put("reply", r.reply());
        }
        result.put("fileValid", r.fileValid());
        return result;
    }

    // ==================== 远程解禁（Dock3 wayline.html） ====================

    /**
     * 查询当前解禁证书列表（license_id → enabled）。
     * <p>平台通过 services 下发 unlock_license_switch 后，证书状态会更新到此列表。</p>
     */
    @GetMapping("/unlock-license/list")
    public Map<String, Object> listUnlockLicenses() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("licenses", unlockLicenseSimulator.getLicenses());
        return result;
    }

    /**
     * 重置解禁证书列表（清空所有证书状态）。
     */
    @PostMapping("/unlock-license/reset")
    public Map<String, Object> resetUnlockLicenses() {
        unlockLicenseSimulator.resetLicenses();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "已清空解禁证书列表");
        return result;
    }

    // ==================== PSDK 喊话器与负载事件（Dock3 wayline.html） ====================

    /**
     * 查询指定 psdk_index 的喊话器状态。
     * <p>请求参数：psdk_index（int，必填）</p>
     */
    @GetMapping("/psdk/state")
    public Map<String, Object> getPsdkState(@RequestParam int psdk_index) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("psdk_index", psdk_index);
        result.put("play_volume", psdkSimulator.getSpeakerVolume(psdk_index));
        result.put("play_mode", psdkSimulator.getSpeakerPlayMode(psdk_index));
        result.put("playing", psdkSimulator.isSpeakerPlaying(psdk_index));
        result.put("default_tts_text", psdkSimulator.getDefaultTtsText());
        result.put("default_tts_md5", psdkSimulator.getDefaultTtsMd5());
        result.put("default_audio_md5", psdkSimulator.getDefaultAudioMd5());
        result.put("last_tts", psdkSimulator.getLastTts(psdk_index));
        result.put("last_audio_file", psdkSimulator.getLastAudioFile(psdk_index));
        result.put("input_box_text", psdkSimulator.getInputBoxText(psdk_index));
        result.put("widget_values", psdkSimulator.getWidgetValues(psdk_index));
        result.put("last_custom_data", psdkSimulator.getLastCustomData());
        return result;
    }

    /**
     * 触发 speaker_tts_play_start_progress 事件（TTS 播放进度通知）。
     * <p>请求体示例：
     * {@code {"psdk_index":2,"status":"in_progress","percent":50,"step_key":"upload","md5":"可选覆盖"}}</p>
     * <p>遵循"业务逻辑返回明确拒绝原因而非抛异常"约定。</p>
     */
    @PostMapping("/psdk/tts-play-progress")
    public Map<String, Object> triggerTtsPlayProgress(@RequestBody Map<String, Object> body) {
        if (body.get("psdk_index") == null) {
            return fail("psdk_index 为必填");
        }
        int psdkIndex = ((Number) body.get("psdk_index")).intValue();
        String status = String.valueOf(body.getOrDefault("status", "in_progress"));
        int percent = ((Number) body.getOrDefault("percent", 0)).intValue();
        String stepKey = String.valueOf(body.getOrDefault("step_key", "upload"));
        String md5Override = body.get("md5") == null ? null : String.valueOf(body.get("md5"));

        PsdkSimulator.TriggerResult r = psdkSimulator.triggerTtsPlayProgress(
                psdkIndex, status, percent, stepKey, md5Override);
        return triggerResultMap(r);
    }

    /**
     * 触发 speaker_audio_play_start_progress 事件（音频播放进度通知）。
     * <p>请求体示例：
     * {@code {"psdk_index":2,"status":"in_progress","percent":89,"step_key":"upload","md5":"可选覆盖"}}</p>
     */
    @PostMapping("/psdk/audio-play-progress")
    public Map<String, Object> triggerAudioPlayProgress(@RequestBody Map<String, Object> body) {
        if (body.get("psdk_index") == null) {
            return fail("psdk_index 为必填");
        }
        int psdkIndex = ((Number) body.get("psdk_index")).intValue();
        String status = String.valueOf(body.getOrDefault("status", "in_progress"));
        int percent = ((Number) body.getOrDefault("percent", 0)).intValue();
        String stepKey = String.valueOf(body.getOrDefault("step_key", "upload"));
        String md5Override = body.get("md5") == null ? null : String.valueOf(body.get("md5"));

        PsdkSimulator.TriggerResult r = psdkSimulator.triggerAudioPlayProgress(
                psdkIndex, status, percent, stepKey, md5Override);
        return triggerResultMap(r);
    }

    /**
     * 触发 psdk_floating_window_text 事件（浮窗文本推送）。
     * <p>请求体示例：{@code {"psdk_index":2,"value":"System time : 1193683 ms"}}</p>
     */
    @PostMapping("/psdk/floating-window-text")
    public Map<String, Object> triggerFloatingWindowText(@RequestBody Map<String, Object> body) {
        if (body.get("psdk_index") == null) {
            return fail("psdk_index 为必填");
        }
        int psdkIndex = ((Number) body.get("psdk_index")).intValue();
        String value = String.valueOf(body.getOrDefault("value", ""));

        PsdkSimulator.TriggerResult r = psdkSimulator.triggerFloatingWindowText(psdkIndex, value);
        return triggerResultMap(r);
    }

    /**
     * 触发 psdk_ui_resource_upload_result 事件（UI 资源包上传结果上报）。
     * <p>请求体示例：
     * {@code {"psdk_index":2,"object_key":"xxx/widget","size":43488,"result":0}}</p>
     */
    @PostMapping("/psdk/ui-resource-upload-result")
    public Map<String, Object> triggerUiResourceUploadResult(@RequestBody Map<String, Object> body) {
        if (body.get("psdk_index") == null) {
            return fail("psdk_index 为必填");
        }
        int psdkIndex = ((Number) body.get("psdk_index")).intValue();
        String objectKey = String.valueOf(body.getOrDefault("object_key", ""));
        long size = ((Number) body.getOrDefault("size", 0)).longValue();
        int result = ((Number) body.getOrDefault("result", 0)).intValue();

        PsdkSimulator.TriggerResult r = psdkSimulator.triggerUiResourceUploadResult(
                psdkIndex, objectKey, size, result);
        return triggerResultMap(r);
    }

    /**
     * PSDK UI 资源完整上传流程：storage_config_get(module=1) → 上传内置占位文件 → 上报 psdk_ui_resource_upload_result 事件。
     * <p>请求体示例：{@code {"psdk_index":2}}</p>
     */
    @PostMapping("/psdk/ui-resource-upload")
    public Map<String, Object> uploadUiResource(@RequestBody Map<String, Object> body) {
        if (body.get("psdk_index") == null) {
            return fail("psdk_index 为必填");
        }
        int psdkIndex = ((Number) body.get("psdk_index")).intValue();

        PsdkSimulator.TriggerResult r = psdkSimulator.uploadUiResource(psdkIndex);
        return triggerResultMap(r);
    }

    /**
     * 触发 custom_data_transmission_from_psdk 事件（PSDK→cloud 自定义消息推送）。
     * <p>请求体示例：{@code {"value":"hello world"}}</p>
     */
    @PostMapping("/psdk/custom-data-from-psdk")
    public Map<String, Object> triggerCustomDataFromPsdk(@RequestBody Map<String, Object> body) {
        if (body.get("value") == null) {
            return fail("value 为必填");
        }
        String value = body.get("value").toString();
        PsdkSimulator.TriggerResult r = psdkSimulator.triggerCustomDataFromPsdk(value);
        return triggerResultMap(r);
    }

    /**
     * 触发 custom_data_transmission_from_esdk 事件（ESDK→cloud 自定义消息推送）。
     * <p>请求体示例：{@code {"value":"hello world"}}</p>
     */
    @PostMapping("/esdk/custom-data-from-esdk")
    public Map<String, Object> triggerCustomDataFromEsdk(@RequestBody Map<String, Object> body) {
        if (body.get("value") == null) {
            return fail("value 为必填");
        }
        String value = body.get("value").toString();
        EsdkSimulator.TriggerResult r = esdkSimulator.triggerCustomDataFromEsdk(value);
        return triggerResultMap(r);
    }

    /**
     * 查询 ESDK 互联互通状态。
     */
    @GetMapping("/esdk/state")
    public Map<String, Object> getEsdkState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("last_custom_data", esdkSimulator.getLastCustomData());
        return result;
    }

    // ==================== 远程日志 ====================

    /**
     * 查询远程日志上传状态。
     */
    @GetMapping("/remote-log/state")
    public Map<String, Object> getRemoteLogState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploading", remoteLogSimulator.isUploading());
        result.put("files", remoteLogSimulator.getCurrentUploadFiles());
        return result;
    }

    /**
     * 手动触发 fileupload_progress 事件。
     * <p>请求体示例：{@code {"status":"ok","percent":100}}</p>
     */
    @PostMapping("/remote-log/progress")
    public Map<String, Object> triggerRemoteLogProgress(@RequestBody Map<String, Object> body) {
        String status = body.getOrDefault("status", "ok").toString();
        int percent = Integer.parseInt(body.getOrDefault("percent", "100").toString());
        RemoteLogSimulator.TriggerResult r = remoteLogSimulator.triggerFileUploadProgress(status, percent);
        return triggerResultMap(r);
    }

    // ==================== 固件升级 ====================

    /**
     * 查询固件升级状态。
     */
    @GetMapping("/ota/state")
    public Map<String, Object> getOtaState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("upgrading", otaSimulator.isUpgrading());
        result.put("devices", otaSimulator.getCurrentUpgradeDevices());
        return result;
    }

    /**
     * 手动触发 ota_progress 事件。
     * <p>请求体示例：{@code {"status":"in_progress","current_step":"download_firmware","percent":50}}</p>
     */
    @PostMapping("/ota/progress")
    public Map<String, Object> triggerOtaProgress(@RequestBody Map<String, Object> body) {
        String status = body.getOrDefault("status", "in_progress").toString();
        String currentStep = body.getOrDefault("current_step", "download_firmware").toString();
        int percent = Integer.parseInt(body.getOrDefault("percent", "50").toString());
        OtaSimulator.TriggerResult r = otaSimulator.triggerOtaProgress(status, currentStep, percent);
        return triggerResultMap(r);
    }

    private Map<String, Object> triggerResultMap(PsdkSimulator.TriggerResult r) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        if (r.code() != null) result.put("code", r.code());
        if (r.message() != null) result.put("message", r.message());
        return result;
    }

    private Map<String, Object> triggerResultMap(EsdkSimulator.TriggerResult r) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        if (r.code() != null) result.put("code", r.code());
        if (r.message() != null) result.put("message", r.message());
        return result;
    }

    private Map<String, Object> triggerResultMap(RemoteLogSimulator.TriggerResult r) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        if (r.code() != null) result.put("code", r.code());
        if (r.message() != null) result.put("message", r.message());
        return result;
    }

    private Map<String, Object> triggerResultMap(OtaSimulator.TriggerResult r) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        if (r.code() != null) result.put("code", r.code());
        if (r.message() != null) result.put("message", r.message());
        return result;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
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

    /**
     * 获取 MQTT 消息日志。
     * <p>无 beforeTime 参数时返回内存缓冲中最近的 N 条；有 beforeTime 时从本地文件加载更早的历史。
     *
     * @param beforeTime 时间戳分界点（毫秒），返回此时间之前的消息。不传则返回内存中最近 N 条
     * @param limit      返回条数（默认 500）
     */
    @GetMapping("/logs")
    public List<Map<String, Object>> getLogs(
            @RequestParam(required = false) Long beforeTime,
            @RequestParam(defaultValue = "500") int limit) {
        if (beforeTime != null) {
            return mqtt.queryHistory(beforeTime, limit);
        }
        return mqtt.getLogs();
    }

    /** 清空消息日志（仅清空内存缓冲，本地文件保留） */
    @DeleteMapping("/logs")
    public Map<String, Object> clearLogs() {
        mqtt.clearLogs();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 导出消息日志数据（从本地文件读取，支持所有消息类型，不仅 OSD）。
     * <p>用法：GET /api/logs/export?sn=7UUXN1Q00A008W&direction=send&limit=500
     *
     * @param sn        设备 SN（多个用逗号分隔，为空则不过滤）
     * @param direction 方向过滤（send/recv，默认 send）
     * @param limit     返回条数（默认 500）
     * @return 消息日志列表，每条含 {ts, time, topic, method, data}
     */
    @GetMapping("/logs/export")
    public List<Map<String, Object>> exportLogs(
            @RequestParam(required = false) String sn,
            @RequestParam(defaultValue = "send") String direction,
            @RequestParam(defaultValue = "500") int limit) {
        // 解析 SN 列表（为空则不过滤）
        List<String> snList = null;
        if (sn != null && !sn.isBlank()) {
            snList = Arrays.stream(sn.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }

        // 从本地文件查询全部消息（limit 放大，后续过滤后再截取）
        List<Map<String, Object>> logs = mqtt.queryHistory(null, limit * 5);
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = logs.size() - 1; i >= 0 && result.size() < limit; i--) {
            Map<String, Object> entry = logs.get(i);
            String entryDirection = String.valueOf(entry.get("direction"));
            String topic = String.valueOf(entry.get("topic"));

            if (!direction.equals(entryDirection)) continue;
            if (snList != null && !snList.isEmpty()) {
                boolean snMatched = snList.stream().anyMatch(topic::contains);
                if (!snMatched) continue;
            }

            String payload = String.valueOf(entry.get("payload"));
            try {
                JsonNode node = objectMapper.readTree(payload);
                JsonNode data = node.path("data");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ts", entry.get("ts"));
                item.put("time", entry.get("time"));
                item.put("topic", topic);
                item.put("method", entry.get("method"));
                item.put("data", objectMapper.treeToValue(data, Object.class));
                result.add(item);
            } catch (Exception e) {
                // payload 非 JSON 或解析失败，跳过
            }
        }

        java.util.Collections.reverse(result);
        return result;
    }

    /**
     * 下载本地消息日志文件（JSON Lines 格式）。
     * <p>用法：GET /api/logs/download?date=2026-08-14（不传 date 则下载当天的）
     */
    @GetMapping("/logs/download")
    public ResponseEntity<byte[]> downloadLogFile(
            @RequestParam(required = false) String date) {
        var store = mqtt.getMessageLogStore();
        if (date == null || date.isBlank()) {
            date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        Path file = store.getLogFile(date);
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] content = Files.readAllBytes(file);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"messages-" + date + ".jsonl\"")
                    .header("Content-Type", "application/jsonl")
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取本地日志文件列表（按日期倒序）。
     */
    @GetMapping("/logs/files")
    public List<Map<String, Object>> listLogFiles() {
        var store = mqtt.getMessageLogStore();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Path file : store.getLogFiles()) {
            Map<String, Object> item = new LinkedHashMap<>();
            String name = file.getFileName().toString();
            // 文件名格式 messages-yyyy-MM-dd.jsonl
            String date = name.replace("messages-", "").replace(".jsonl", "");
            item.put("date", date);
            item.put("name", name);
            try {
                item.put("size", Files.size(file));
            } catch (Exception e) {
                item.put("size", 0);
            }
            result.add(item);
        }
        return result;
    }

    // ==================== 连接状态与配置 ====================

    /**
     * 开机（记录电源状态，幂等）。
     * <p>电源状态由后端管理（单一真相源）：前端刷新页面后通过 /api/connection 恢复，
     * 避免仅存前端内存导致刷新后回到「可开机」状态。开机≠注册上线，本接口不建立 MQTT 连接。</p>
     */
    @PostMapping("/power/on")
    public Map<String, Object> powerOn() {
        state.setPowered(true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("powered", state.isPowered());
        return result;
    }

    /** 获取 MQTT 连接状态 */
    @GetMapping("/connection")
    public Map<String, Object> getConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mqtt_connected", mqtt.isConnected());
        result.put("online", state.isOnline());
        result.put("powered", state.isPowered());
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
        result.put("selected_payload", runtimeConfig.getSelectedPayload() != null ? runtimeConfig.getSelectedPayload().name() : null);
        result.put("controller_sn", runtimeConfig.getControllerSn());
        result.put("dock_sn", runtimeConfig.getDockSn());
        result.put("drone_sn", runtimeConfig.getDroneSn());
        result.put("thing_version", runtimeConfig.getThingVersion());
        return result;
    }

    /**
     * 获取 Pilot 上云配置（HTTP/WS 接口 + map 模块 + media/live/mop 模块）。
     * <p>供前端 Pilot 上云页面加载已保存的 token / map 配置及 media/live/mop 参数。
     */
    @GetMapping("/config/pilot")
    public Map<String, Object> getPilotConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("http_base_url", runtimeConfig.getHivemindHttpBaseUrl());
        result.put("http_token", runtimeConfig.getHivemindHttpToken());
        result.put("ws_url", runtimeConfig.getHivemindWsUrl());
        result.put("ws_token", runtimeConfig.getHivemindWsToken());
        result.put("map_user_name", runtimeConfig.getMapUserName());
        result.put("map_element_pre_name", runtimeConfig.getMapElementPreName());
        result.put("media_auto_upload_photo", runtimeConfig.isMediaAutoUploadPhoto());
        result.put("media_auto_upload_photo_type", runtimeConfig.getMediaAutoUploadPhotoType());
        result.put("media_auto_upload_video", runtimeConfig.isMediaAutoUploadVideo());
        result.put("media_download_owner", runtimeConfig.getMediaDownloadOwner());
        result.put("live_video_publish_type", runtimeConfig.getLiveVideoPublishType());
        result.put("mop_host", runtimeConfig.getMopHost());
        result.put("mop_token", runtimeConfig.getMopToken());
        result.put("mop_connected", mopClient.isConnected());
        return result;
    }

    /**
     * 更新 Pilot 上云配置（HTTP/WS 接口 + map 模块 + media/live/mop 模块）。
     * <p>支持部分更新：仅更新请求中包含的字段。
     * <p>更新后自动持久化到 LiveConfigStore（config 卷），重启后自动恢复，避免用户重复配置 token/地址。
     */
    @PostMapping("/config/pilot")
    public Map<String, Object> updatePilotConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("http_base_url")) {
            String url = (String) body.get("http_base_url");
            runtimeConfig.setHivemindHttpBaseUrl(url != null ? url : "");
        }
        if (body.containsKey("http_token")) {
            String token = (String) body.get("http_token");
            runtimeConfig.setHivemindHttpToken(token != null ? token : "");
        }
        if (body.containsKey("ws_url")) {
            String url = (String) body.get("ws_url");
            runtimeConfig.setHivemindWsUrl(url != null ? url : "");
        }
        if (body.containsKey("ws_token")) {
            String token = (String) body.get("ws_token");
            runtimeConfig.setHivemindWsToken(token != null ? token : "");
        }
        if (body.containsKey("map_user_name")) {
            String userName = (String) body.get("map_user_name");
            runtimeConfig.setMapUserName(userName != null ? userName : "");
        }
        if (body.containsKey("map_element_pre_name")) {
            String preName = (String) body.get("map_element_pre_name");
            runtimeConfig.setMapElementPreName(preName != null ? preName : "");
        }
        if (body.containsKey("media_auto_upload_photo")) {
            runtimeConfig.setMediaAutoUploadPhoto(Boolean.parseBoolean(String.valueOf(body.get("media_auto_upload_photo"))));
        }
        if (body.containsKey("media_auto_upload_photo_type")) {
            runtimeConfig.setMediaAutoUploadPhotoType(parseIntValue(body.get("media_auto_upload_photo_type")));
        }
        if (body.containsKey("media_auto_upload_video")) {
            runtimeConfig.setMediaAutoUploadVideo(Boolean.parseBoolean(String.valueOf(body.get("media_auto_upload_video"))));
        }
        if (body.containsKey("media_download_owner")) {
            runtimeConfig.setMediaDownloadOwner(parseIntValue(body.get("media_download_owner")));
        }
        if (body.containsKey("live_video_publish_type")) {
            String type = (String) body.get("live_video_publish_type");
            runtimeConfig.setLiveVideoPublishType(type != null ? type : "video-on-demand");
        }
        if (body.containsKey("mop_host")) {
            String host = (String) body.get("mop_host");
            runtimeConfig.setMopHost(host != null ? host : "");
        }
        if (body.containsKey("mop_token")) {
            String token = (String) body.get("mop_token");
            runtimeConfig.setMopToken(token != null ? token : "");
        }
        // 持久化到 LiveConfigStore，重启后自动恢复（避免用户重复配置 token/地址）
        runtimeConfig.persistLiveConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    /** 安全解析 Integer，支持 Number 和 String 输入，解析失败返回 0 */
    private int parseIntValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== MOP 数据传输（Pilot 上云） ====================

    /** 连接 MOP WebSocket 通道 */
    @PostMapping("/mop/connect")
    public Map<String, Object> mopConnect() {
        mopClient.connect();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mop_connected", mopClient.isConnected());
        return result;
    }

    /** 断开 MOP WebSocket 通道 */
    @PostMapping("/mop/disconnect")
    public Map<String, Object> mopDisconnect() {
        mopClient.disconnect();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mop_connected", mopClient.isConnected());
        return result;
    }

    /** 查询 MOP 连接状态 */
    @GetMapping("/mop/status")
    public Map<String, Object> mopStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mop_connected", mopClient.isConnected());
        result.put("mop_host", runtimeConfig.getMopHost());
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
                runtimeConfig.setDockType(DockModel.valueOf(String.valueOf(config.get("dock_type")).trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (config.containsKey("drone_type") && config.get("drone_type") != null) {
            try {
                runtimeConfig.setDroneType(DroneModel.valueOf(String.valueOf(config.get("drone_type")).trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (config.containsKey("device_mode") && config.get("device_mode") != null) {
            try {
                runtimeConfig.setDeviceMode(DeviceMode.valueOf(String.valueOf(config.get("device_mode")).trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (config.containsKey("controller_type") && config.get("controller_type") != null) {
            try {
                runtimeConfig.setControllerType(RcModel.valueOf(String.valueOf(config.get("controller_type")).trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (config.containsKey("selected_payload")) {
            Object val = config.get("selected_payload");
            if (val == null || String.valueOf(val).isBlank()) {
                runtimeConfig.setSelectedPayload(null);
            } else {
                try {
                    runtimeConfig.setSelectedPayload(PayloadType.valueOf(String.valueOf(val).trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (config.containsKey("thing_version") && !String.valueOf(config.get("thing_version")).isBlank()) {
            runtimeConfig.setThingVersion(String.valueOf(config.get("thing_version")).trim());
        }

        // 幂等优化（TC-MQTT-014）：MQTT 已连接且设备在线时，不重连不重置 online，
        // 让后续 /api/online 的幂等保护（if state.isOnline() return ok()）生效，
        // 避免重复点击注册/开机按钮触发重复 update_topo 推送（平台误以为设备拓扑变化）
        // 仅 MQTT 真断开（首次连接或掉线重连）或设备已离线时，才重置 online + 重连
        DiagnosticCode connectCode;
        if (mqtt.isConnected() && state.isOnline()) {
            connectCode = null;  // 已连接且在线：幂等返回成功，不重连不重置 online
        } else {
            // 重连前若设备在线，先标记离线（重连后需重新上线）
            if (state.isOnline()) {
                state.setOnline(false);
            }
            connectCode = mqtt.reconnect();
        }

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

    // ==================== 地图元素（Pilot 上云 HTTP API） ====================

    /** 获取地图元素列表 */
    @GetMapping("/api/map/elements")
    public Map<String, Object> getMapElements(@RequestParam(required = false) String groupId) {
        var resp = mapElementSimulator.fetchElements(groupId);
        return Map.of("success", resp.success(), "code", resp.code(),
                "message", resp.message(), "data", resp.data() != null ? resp.data() : "");
    }

    /** 创建地图元素 */
    @PostMapping("/api/map/elements")
    public Map<String, Object> createMapElement(@RequestParam String groupId, @RequestBody JsonNode body) {
        var resp = mapElementSimulator.createElement(groupId, body);
        return Map.of("success", resp.success(), "code", resp.code(),
                "message", resp.message(), "data", resp.data() != null ? resp.data() : "");
    }

    /** 更新地图元素 */
    @PutMapping("/api/map/elements/{elementId}")
    public Map<String, Object> updateMapElement(@PathVariable String elementId, @RequestBody JsonNode body) {
        var resp = mapElementSimulator.updateElement(elementId, body);
        return Map.of("success", resp.success(), "code", resp.code(),
                "message", resp.message(), "data", resp.data() != null ? resp.data() : "");
    }

    /** 删除地图元素 */
    @DeleteMapping("/api/map/elements/{elementId}")
    public Map<String, Object> deleteMapElement(@PathVariable String elementId) {
        var resp = mapElementSimulator.deleteElement(elementId);
        return Map.of("success", resp.success(), "code", resp.code(),
                "message", resp.message(), "data", resp.data() != null ? resp.data() : "");
    }

    // ==================== 地图元素 WebSocket 推送事件历史（Pilot 上云） ====================

    /** 查询 WebSocket 推送事件历史 */
    @GetMapping("/api/map/ws-events")
    public Map<String, Object> getMapWsEvents() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，WebSocket 功能不可用");
        }
        return Map.of(
                "success", true,
                "events", mapElementSimulator.getWsEvents(),
                "count", mapElementSimulator.getWsEventCount(),
                "wsConnected", mapElementSimulator.isWsConnected()
        );
    }

    /** 清空 WebSocket 推送事件历史 */
    @DeleteMapping("/api/map/ws-events")
    public Map<String, Object> clearMapWsEvents() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，WebSocket 功能不可用");
        }
        mapElementSimulator.clearWsEvents();
        return Map.of("success", true, "message", "已清空");
    }

    // ==================== 态势感知（Pilot 上云） ====================

    /** 手动触发获取设备拓扑列表 */
    @GetMapping("/api/tsa/device-topo")
    public Map<String, Object> fetchDeviceTopo() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，态势感知功能不可用");
        }
        var resp = situationAwarenessSimulator.fetchDeviceTopo();
        return Map.of("success", resp.success(), "code", resp.code(),
                "message", resp.message(), "data", resp.data() != null ? resp.data() : "");
    }

    /** 查询态势感知 WebSocket 推送事件历史 */
    @GetMapping("/api/tsa/ws-events")
    public Map<String, Object> getTsaWsEvents() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，WebSocket 功能不可用");
        }
        return Map.of(
                "success", true,
                "events", situationAwarenessSimulator.getWsEvents(),
                "count", situationAwarenessSimulator.getWsEventCount()
        );
    }

    /** 清空态势感知 WebSocket 推送事件历史 */
    @DeleteMapping("/api/tsa/ws-events")
    public Map<String, Object> clearTsaWsEvents() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，WebSocket 功能不可用");
        }
        situationAwarenessSimulator.clearWsEvents();
        return Map.of("success", true, "message", "已清空");
    }

    // ==================== Pilot 上云 HTTP 接口（媒体/存储/航线） ====================

    // ---------- 存储服务 ----------

    /** 获取上传临时凭证（STS） */
    @PostMapping("/storage/sts")
    public Map<String, Object> getSts() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        var resp = pilotHttpSimulator.getSts();
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    // ---------- 媒体管理 ----------

    /** 文件快传（秒传） */
    @PostMapping("/media/fast-upload")
    public Map<String, Object> mediaFastUpload(@RequestBody JsonNode body) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        var resp = pilotHttpSimulator.fastUpload(body);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    /** 获取已存在的精简指纹 */
    @PostMapping("/media/tiny-fingerprints")
    public Map<String, Object> getTinyFingerprints(@RequestBody JsonNode body) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        java.util.List<String> fingerprints = new java.util.ArrayList<>();
        if (body.isArray()) {
            body.forEach(n -> fingerprints.add(n.asText()));
        }
        var resp = pilotHttpSimulator.getTinyFingerprints(fingerprints);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    /** 媒体文件上传结果上报 */
    @PostMapping("/media/upload-callback")
    public Map<String, Object> mediaUploadCallback(@RequestBody JsonNode body) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        var resp = pilotHttpSimulator.mediaUploadCallback(body);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    /** 文件组上传完成后回调 */
    @PostMapping("/media/group-upload-callback")
    public Map<String, Object> groupUploadCallback(@RequestBody JsonNode body) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        var resp = pilotHttpSimulator.groupUploadCallback(body);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    // ---------- 航线管理 ----------

    /** 获取航线文件列表 */
    @GetMapping("/wayline/list")
    public Map<String, Object> getWaylines(
            @RequestParam(required = false) Boolean favorited,
            @RequestParam(required = false) String orderBy,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) List<Integer> templateTypes,
            @RequestParam(required = false) Integer actionType,
            @RequestParam(required = false) List<String> droneModelKeys,
            @RequestParam(required = false) List<String> payloadModelKeys) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        var resp = pilotHttpSimulator.getWaylines(favorited, orderBy, page, pageSize,
                templateTypes, actionType, droneModelKeys, payloadModelKeys);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    /** 获取航线文件下载地址 */
    @GetMapping("/wayline/{id}/url")
    public Map<String, Object> getWaylineUrl(@PathVariable String id) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        var resp = pilotHttpSimulator.getWaylineUrl(id);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    /** 获取重复的航线文件名称 */
    @GetMapping("/wayline/duplicate-names")
    public Map<String, Object> getDuplicateWaylineNames(@RequestParam List<String> names) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        var resp = pilotHttpSimulator.getDuplicateWaylineNames(names);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    /** 航线文件上传结果上报 */
    @PostMapping("/wayline/upload-callback")
    public Map<String, Object> waylineUploadCallback(@RequestBody JsonNode body) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        var resp = pilotHttpSimulator.waylineUploadCallback(body);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    /** 批量收藏航线文件 */
    @PostMapping("/wayline/favorites")
    public Map<String, Object> addWaylineFavorites(@RequestBody JsonNode body) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (body.has("id") && body.get("id").isArray()) {
            body.get("id").forEach(n -> ids.add(n.asText()));
        }
        var resp = pilotHttpSimulator.addWaylineFavorites(ids);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    /** 批量取消收藏航线文件 */
    @DeleteMapping("/wayline/favorites")
    public Map<String, Object> removeWaylineFavorites(@RequestParam List<String> ids) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return Map.of("success", false, "message", "非 Pilot 模式，HTTP 接口不可用");
        }
        var resp = pilotHttpSimulator.removeWaylineFavorites(ids);
        return Map.of(
                "success", resp.success(),
                "code", resp.code(),
                "message", resp.message(),
                "data", resp.data() != null ? resp.data() : "-"
        );
    }

    // ==================== 健康检查 ====================

    /**
     * 应用健康检查接口。
     * <p>供 Docker HEALTHCHECK、K8s liveness/readiness Probe 及运维监控调用。
     * <p>返回内容：
     * <ul>
     *   <li>status = UP（HTTP 200，不区分 MQTT/设备状态，应用存活即 UP；如需 readiness 请单独调用 /api/connection）</li>
     *   <li>app / version：应用名和版本（Jar MANIFEST 读取，IDE 开发模式为 dev）</li>
     *   <li>mqtt_connected：MQTT 是否已连接（用于 readiness 判断）</li>
     *   <li>device_online：设备是否已上线（update_topo 已发送）</li>
     *   <li>device_mode：当前接入模式 DOCK / PILOT</li>
     *   <li>ts：服务端当前时间戳（毫秒）</li>
     * </ul>
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("app", "dji-dock-simulator");
        result.put("version", appVersion);
        result.put("mqtt_connected", mqtt.isConnected());
        result.put("device_online", state.isOnline());
        result.put("device_mode", runtimeConfig.getDeviceMode().name());
        result.put("ts", System.currentTimeMillis());
        return result;
    }
}
