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
import ltd.cdmi.dji.cloudapi.sdk.codec.MessageCodec;
import ltd.cdmi.dji.cloudapi.sdk.command.service.drc.DrcModeEnterRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.pilot.CloudControlAuthRequest;
import ltd.cdmi.dji.cloudapi.sdk.protocol.envelope.EventEnvelope;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.EventMethod;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.ServiceMethod;
import ltd.cdmi.dji.cloudapi.sdk.telemetry.OsdField;
import ltd.cdmi.dji.cloudapi.sdk.telemetry.StateField;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 云控授权与 DRC 模式切换处理器。
 * <p>统一处理 Pilot 上云特有的云控授权流程和两种模式共用的 DRC 模式切换：</p>
 * <ul>
 *   <li>{@code cloud_control_auth_request}（仅 Pilot）：平台请求授权，模拟器自动同意，
 *       回 services_reply + events(cloud_control_auth_notify) + state(cloud_control_auth)</li>
 *   <li>{@code cloud_control_release}（仅 Pilot）：平台释放授权，
 *       回 services_reply + state(cloud_control_auth=[])</li>
 *   <li>{@code drc_mode_enter}（Pilot + Dock）：进入 DRC 模式，解析 mqtt_broker 信息，
 *       设 drcState=2(已连接) + state(drc_state)</li>
 *   <li>{@code drc_mode_exit}（Pilot + Dock）：退出 DRC 模式，设 drcState=0(空闲) + state(drc_state)</li>
 * </ul>
 * <p>核实依据：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/dji-rc-plus-2/drc.html">DJI Pilot DRC 指令飞行</a></p>
 * <p><b>DRC 心跳说明</b>：DJI 官方文档未明确定义 heart_beat 的协议格式和发起方。
 * 废弃的 drc_status_notify 注释仅提到"DRC-心跳"可感知链路状态，但未说明方向。
 * 现有 {@link DrcCommandHandler} 已注册 heart_beat handler，接收 drc/down（平台→设备）并响应 drc/up（设备→云），
 * 采用请求-响应模式。本类不实现设备主动发送心跳。</p>
 * <p>mqtt_broker 信息供平台端建立 DRC 专用 MQTT 连接，模拟器作为设备端继续使用现有连接。</p>
 */
@Component
public class AuthFlowHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthFlowHandler.class);

    /** DRC 模式切换指令（Pilot + Dock 共用） */
    private static final Set<ServiceMethod> DRC_MODE_METHODS = EnumSet.of(
            ServiceMethod.DRC_MODE_ENTER, ServiceMethod.DRC_MODE_EXIT);

    /** 云控授权指令（仅 Pilot） */
    private static final Set<ServiceMethod> CLOUD_CONTROL_METHODS = EnumSet.of(
            ServiceMethod.CLOUD_CONTROL_AUTH_REQUEST, ServiceMethod.CLOUD_CONTROL_RELEASE);

    /**
     * auth_request 成功后授予的控制权列表。
     * <p>DJI 文档未明确 cloud_control_auth 的具体值示例，模拟器采用简化策略：
     * 授权成功后置为 ["flight"]，表示授予飞行控制权。待真机验证。</p>
     */
    private static final List<String> AUTH_GRANTED = List.of("flight");

    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final DockTopicSchema dockTopicSchema;

    /** 当前云控授权列表（pushMode=1 状态），初始为空 */
    private volatile List<String> cloudControlAuth = List.of();

    /** 授权流程涉及并发访问（auth_request/release 可能来自不同线程），使用线程安全列表 */
    private final CopyOnWriteArrayList<String> authList = new CopyOnWriteArrayList<>();

    public AuthFlowHandler(MqttClientManager mqtt, DeviceState state,
                           RuntimeConfig runtimeConfig,
                           DiagnosticLogRecorder diagnosticRecorder,
                           DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.state = state;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
        this.dockTopicSchema = dockTopicSchema;
    }

    /**
     * 判断是否处理该指令。
     * <p>DRC 模式切换指令两种模式都处理；云控授权指令仅 Pilot 模式处理。</p>
     */
    public boolean handles(String method) {
        Optional<ServiceMethod> svcMethod = ServiceMethod.fromMethodName(method);
        if (svcMethod.isEmpty()) {
            return false;
        }
        if (DRC_MODE_METHODS.contains(svcMethod.get())) {
            return true;
        }
        if (CLOUD_CONTROL_METHODS.contains(svcMethod.get()) && runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
            return true;
        }
        return false;
    }

    /**
     * 处理指令，返回 output（含 result 字段）。
     * @param method 指令方法名
     * @param data 指令数据
     * @param bid 业务 ID（用于 events 事件关联）
     */
    public Map<String, Object> handle(String method, JsonNode data, String bid) {
        ServiceMethod svcMethod = ServiceMethod.fromMethodName(method).orElse(null);
        if (svcMethod == null) {
            log.warn("[AuthFlow] 未覆盖指令: method={}", method);
            return Map.of("result", 0);
        }
        return switch (svcMethod) {
            case DRC_MODE_ENTER -> handleDrcModeEnter(data);
            case DRC_MODE_EXIT -> handleDrcModeExit();
            case CLOUD_CONTROL_AUTH_REQUEST -> handleAuthRequest(data, bid);
            case CLOUD_CONTROL_RELEASE -> handleRelease(data);
            default -> {
                log.warn("[AuthFlow] 未覆盖指令: method={}", method);
                yield Map.of("result", 0);
            }
        };
    }

    // ==================== DRC 模式切换（Pilot + Dock） ====================

    /**
     * 进入 DRC 模式。
     * <p>解析 drc_mode_enter 指令 data 中的 mqtt_broker 连接信息和上报频率，
     * 设 drcState=2(已连接)，通过 state topic 上报 drc_state 变更。</p>
     * <p>DJI 协议：mqtt_broker 信息供平台端建立 DRC 专用 MQTT 连接，模拟器作为设备端继续使用现有连接。</p>
     * <p>DRC 心跳：DJI 官方文档未明确设备需主动发送心跳。现有 DrcCommandHandler 已处理平台发起的心跳响应
     * （drc/down → drc/up 请求-响应模式），本类不再实现设备主动心跳。</p>
     * @param data 指令数据，含 mqtt_broker、hsi_frequency、osd_frequency
     */
    private Map<String, Object> handleDrcModeEnter(JsonNode data) {
        // 空值防御（TC-DRC-056）：SDK MessageCodec 对缺失 mqtt_broker 的 data 抛 IllegalState
        // （mqttBroker 必填校验）。调试器等工具下发的 drc_mode_enter 可能不带 mqtt_broker，
        // broker 信息在模拟器中仅用于日志（不建专用 DRC 连接），解析失败时跳过，不阻断 DRC 模式进入
        DrcModeEnterRequest req = null;
        try {
            req = MessageCodec.fromJson(data.toString(), DrcModeEnterRequest.class);
        } catch (Exception e) {
            log.warn("drc_mode_enter data 解析失败（{}），跳过 mqtt_broker/频率日志（不影响进入 DRC 模式）", e.getMessage());
        }
        var broker = req != null ? req.mqttBroker() : null;
        if (broker != null) {
            log.info("DRC MQTT broker 信息: address={}, client_id={}, username={}, enable_tls={}, expire_time={}",
                    broker.address(),
                    broker.clientId(),
                    broker.username(),
                    broker.enableTls(),
                    broker.expireTime());
            log.info("DRC 上报频率: hsi_frequency={}, osd_frequency={}", req.hsiFrequency(), req.osdFrequency());
        }

        state.setDrcState(2);
        log.info("已进入 DRC 模式");
        publishDrcState(2);

        // M-2 诊断日志：模拟器不建立专用 DRC MQTT 连接，使用现有连接
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                ServiceMethod.DRC_MODE_ENTER.methodName(),
                "mqtt_broker 信息供平台建立专用连接，模拟器作为设备端继续使用现有 MQTT 连接。"
                + "DJI 文档未明确设备需主动发送 DRC 心跳，现有 DrcCommandHandler 已处理平台发起的心跳响应");

        return Map.of("result", 0);
    }

    /**
     * 退出 DRC 模式。
     * <p>设 drcState=0(空闲)，通过 state topic 上报 drc_state 变更。</p>
     */
    private Map<String, Object> handleDrcModeExit() {
        state.setDrcState(0);
        log.info("已退出 DRC 模式");
        publishDrcState(0);
        return Map.of("result", 0);
    }

    // ==================== 云控授权（仅 Pilot） ====================

    /**
     * 处理云控授权请求。
     * <p>模拟器自动同意授权（模拟用户在遥控器上点击同意）：</p>
     * <ol>
     *   <li>通过 events 上报 {@code cloud_control_auth_notify}（status=ok）</li>
     *   <li>通过 state 上报 {@code cloud_control_auth}（授权列表）</li>
     * </ol>
     * <p>DJI 文档 services_reply 示例包含 {@code output: {status: "ok"}}，与 {@code cloud_control_auth_notify}
     * 的 output.status 一致，表示授权结果。</p>
     * @param data 指令数据，含 {@code user_id}、{@code user_callsign}、{@code control_keys}
     * @param bid 业务 ID，用于 events 事件关联
     */
    private Map<String, Object> handleAuthRequest(JsonNode data, String bid) {
        var req = MessageCodec.fromJson(data.toString(), CloudControlAuthRequest.class);
        log.info("收到云控授权请求: user_id={}, user_callsign={}, 自动同意", req.userId(), req.userCallsign());

        // 更新授权列表
        authList.clear();
        authList.addAll(AUTH_GRANTED);
        cloudControlAuth = List.copyOf(authList);

        // 上报授权结果通知（events）
        publishAuthNotify(bid, "ok");

        // 上报授权状态（state）
        publishCloudControlAuth();

        // M-2 诊断日志：cloud_control_auth 具体值待真机验证
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                ServiceMethod.CLOUD_CONTROL_AUTH_REQUEST.methodName(),
                "cloud_control_auth 值采用简化策略 [\"flight\"]，DJI 文档未明确示例，待真机验证");

        return successReply();
    }

    /**
     * 处理云控授权释放。
     * <p>清空授权列表，通过 state 上报 {@code cloud_control_auth}（空数组）。</p>
     * <p>DJI 文档 services_reply 示例包含 {@code output: {status: "ok"}}。</p>
     * @param data 指令数据，含 {@code control_keys}
     */
    private Map<String, Object> handleRelease(JsonNode data) {
        log.info("收到云控授权释放指令，清空授权");

        authList.clear();
        cloudControlAuth = List.of();

        publishCloudControlAuth();
        return successReply();
    }

    /**
     * 构造成功的 services_reply output（含 result + output.status）。
     * <p>DJI 文档 cloud_control_auth_request/cloud_control_release 的 services_reply 示例均包含
     * {@code {result: 0, output: {status: "ok"}}}。</p>
     */
    private Map<String, Object> successReply() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "ok");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", 0);
        result.put("output", output);
        return result;
    }

    // ==================== MQTT 发布 ====================

    /**
     * 通过 state topic 上报 DRC 状态变更。
     * <p>格式：{@code {tid, bid, timestamp, gateway, data:{"drc_state":N}}}</p>
     */
    private void publishDrcState(int drcState) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(OsdField.DRC_STATE.fieldName(), drcState);

        Map<String, Object> stateMsg = new LinkedHashMap<>();
        stateMsg.put("tid", UUID.randomUUID().toString());
        stateMsg.put("bid", UUID.randomUUID().toString());
        stateMsg.put("timestamp", System.currentTimeMillis());
        stateMsg.put("gateway", runtimeConfig.getGatewaySn());
        stateMsg.put("data", data);

        String stateTopic = dockTopicSchema.topic(dockTopicSchema.state(), runtimeConfig.getGatewaySn());
        mqtt.publishJson(stateTopic, stateMsg);
        log.info("已上报 DRC 状态: drc_state={} via state topic", drcState);
    }

    /**
     * 通过 state topic 上报 cloud_control_auth 授权列表。
     * <p>格式：{@code {tid, bid, timestamp, gateway, data:{"cloud_control_auth":[...]}}}</p>
     */
    private void publishCloudControlAuth() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(StateField.CLOUD_CONTROL_AUTH.fieldName(), cloudControlAuth);

        Map<String, Object> stateMsg = new LinkedHashMap<>();
        stateMsg.put("tid", UUID.randomUUID().toString());
        stateMsg.put("bid", UUID.randomUUID().toString());
        stateMsg.put("timestamp", System.currentTimeMillis());
        stateMsg.put("gateway", runtimeConfig.getGatewaySn());
        stateMsg.put("data", data);

        String stateTopic = dockTopicSchema.topic(dockTopicSchema.state(), runtimeConfig.getGatewaySn());
        mqtt.publishJson(stateTopic, stateMsg);
        log.info("已上报云控授权状态: cloud_control_auth={} via state topic", cloudControlAuth);
    }

    /**
     * 通过 events topic 上报云控授权结果通知。
     * <p>DJI Cloud API 规范：cloud_control_auth_notify 为通知事件，need_reply=0。</p>
     * <p>格式：{@code {bid, tid, timestamp, need_reply, gateway, method, data:{result, output:{status}}}}</p>
     * @param bid 业务 ID，与 auth_request 的 bid 一致
     * @param status 授权状态（ok/failed/canceled）
     */
    private void publishAuthNotify(String bid, String status) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", status);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", 0);
        data.put("output", output);

        EventEnvelope envelope = EventEnvelope.of(
                UUID.randomUUID().toString(),
                bid,
                System.currentTimeMillis(),
                EventMethod.CLOUD_CONTROL_AUTH_NOTIFY, data, runtimeConfig.getGatewaySn());

        String eventsTopic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getGatewaySn());
        mqtt.publish(eventsTopic, MessageCodec.toJson(envelope));
        log.info("已上报云控授权结果通知: status={} via events topic", status);
    }
}
