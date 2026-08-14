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
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    /** 指令飞行命令（drc.html，委托 FlightCommandSimulator） */
    private static final Set<String> FLY_METHODS = Set.of(
            "fly_to_point", "fly_to_point_stop", "fly_to_point_update",
            "takeoff_to_point",
            "flight_authority_grab", "payload_authority_grab",
            "poi_mode_enter", "poi_mode_exit", "poi_circle_speed_set"
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
    private final FlightAreaSimulator flightAreaSimulator;
    private final UnlockLicenseSimulator unlockLicenseSimulator;
    private final PsdkSimulator psdkSimulator;
    private final EsdkSimulator esdkSimulator;
    private final RemoteLogSimulator remoteLogSimulator;
    private final OtaSimulator otaSimulator;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final CoverageRecorder coverageRecorder;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;
    private final PayloadControlHandler payloadControlHandler;
    private final AuthFlowHandler authFlowHandler;

    /** 动态注册的命令处理器：method → (data, tid, bid) → output */
    private BiFunction<String, JsonNode, Map<String, Object>> waylineHandler;
    private BiFunction<String, JsonNode, Map<String, Object>> liveHandler;
    private BiFunction<String, JsonNode, Map<String, Object>> mediaHandler;

    public ServiceCommandHandler(SimulatorProperties props, MqttClientManager mqtt,
                                 DeviceState state, ObjectMapper objectMapper,
                                 FlightCommandSimulator flightCommandSimulator,
                                 RemoteDebugSimulator remoteDebugSimulator,
                                 FlightAreaSimulator flightAreaSimulator,
                                 UnlockLicenseSimulator unlockLicenseSimulator,
                                 PsdkSimulator psdkSimulator,
                                 EsdkSimulator esdkSimulator,
                                 RemoteLogSimulator remoteLogSimulator,
                                 OtaSimulator otaSimulator,
                                 DiagnosticLogRecorder diagnosticRecorder,
                                 CoverageRecorder coverageRecorder,
                                 RuntimeConfig runtimeConfig,
                                 DockTopicSchema dockTopicSchema,
                                 PayloadControlHandler payloadControlHandler,
                                 AuthFlowHandler authFlowHandler) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.flightCommandSimulator = flightCommandSimulator;
        this.remoteDebugSimulator = remoteDebugSimulator;
        this.flightAreaSimulator = flightAreaSimulator;
        this.unlockLicenseSimulator = unlockLicenseSimulator;
        this.psdkSimulator = psdkSimulator;
        this.esdkSimulator = esdkSimulator;
        this.remoteLogSimulator = remoteLogSimulator;
        this.otaSimulator = otaSimulator;
        this.diagnosticRecorder = diagnosticRecorder;
        this.coverageRecorder = coverageRecorder;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
        this.payloadControlHandler = payloadControlHandler;
        this.authFlowHandler = authFlowHandler;
    }

    @PostConstruct
    public void init() {
        String gatewaySn = runtimeConfig.getGatewaySn();
        mqtt.addListener(dockTopicSchema.topic(dockTopicSchema.services(), gatewaySn), this::handleService);
        log.info("ServiceCommandHandler 已注册监听: {}", dockTopicSchema.topic(dockTopicSchema.services(), gatewaySn));
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
        // DRC 模式切换 + 云控授权（委托 AuthFlowHandler）
        if (authFlowHandler.handles(method)) {
            return authFlowHandler.handle(method, data, bid);
        }

        // 指令飞行命令（drc.html）
        if (FLY_METHODS.contains(method)) {
            return flightCommandSimulator.handle(method, data, bid);
        }

        // 负载控制命令（drc.html，委托 PayloadControlHandler）
        if (payloadControlHandler.handles(method)) {
            return payloadControlHandler.handle(method, data);
        }

        // 远程调试命令（cmd.html）
        if (RemoteDebugSimulator.isRemoteDebugMethod(method)) {
            return remoteDebugSimulator.handle(method, data, bid);
        }

        // 自定义飞行区更新指令（wayline.html，Dock1/Dock2/Dock3）：回 result=0 并自动联动 flight_areas_get
        if ("flight_areas_update".equals(method)) {
            return flightAreaSimulator.handleServiceUpdate();
        }

        // 远程解禁指令（wayline.html，Dock1/Dock2/Dock3）：同步 Service，回 result=0
        if (UnlockLicenseSimulator.isUnlockLicenseMethod(method)) {
            if ("unlock_license_switch".equals(method)) {
                return unlockLicenseSimulator.handleSwitch(data);
            }
            if ("unlock_license_list".equals(method)) {
                return unlockLicenseSimulator.handleList(data);
            }
            return unlockLicenseSimulator.handleUpdate(data);
        }

        // PSDK 喊话器指令（wayline.html，Dock3）：同步 Service，回 result=0
        if (PsdkSimulator.isPsdkServiceMethod(method)) {
            return psdkSimulator.handleService(method, data);
        }

        // ESDK 互联互通指令（Dock1/Dock2/Dock3）：同步 Service，回 result=0
        if (EsdkSimulator.isEsdkServiceMethod(method)) {
            return esdkSimulator.handleService(method, data);
        }

        // 远程日志指令（Dock1/Dock2/Dock3）：同步 Service，回 result=0，fileupload_start 异步模拟进度
        if (RemoteLogSimulator.isRemoteLogServiceMethod(method)) {
            return remoteLogSimulator.handleService(method, data);
        }

        // 固件升级指令（Dock1/Dock2/Dock3）：同步 Service，回 {result:0, output:{status:"in_progress"}}，异步模拟进度
        if (OtaSimulator.isOtaServiceMethod(method)) {
            return otaSimulator.handleService(method, data);
        }

        // 其他未实现的命令：统一占位 result=0
        log.warn("[S-2] 未覆盖指令占位应答: method={}", method);
        diagnosticRecorder.record(DiagnosticCode.SIMULATOR_METHOD_NOT_IMPLEMENTED, method, "未覆盖指令占位应答");
        return Map.of("result", 0);
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
        reply.put("gateway", runtimeConfig.getGatewaySn());
        reply.put("method", method);
        reply.put("data", data);

        String replyTopic = dockTopicSchema.topic(dockTopicSchema.servicesReply(), runtimeConfig.getGatewaySn());
        mqtt.publishJson(replyTopic, reply);
        log.info("已回复 services_reply: method={}, tid={}, result={}", method, tid, data.get("result"));
    }
}
