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
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AuthFlowHandler 单元测试。
 * <p>覆盖：
 * <ul>
 *   <li>TC-PILOT-013：cloud_control_auth_request 自动同意授权（Pilot 专属）</li>
 *   <li>TC-PILOT-014：cloud_control_release 清空授权（Pilot 专属）</li>
 *   <li>TC-PILOT-014a：drc_mode_enter 解析 MQTT broker 信息并设 drcState=2</li>
 *   <li>TC-PILOT-014b：drc_mode_exit 设 drcState=0</li>
 *   <li>handles() 模式守卫：Dock 模式不处理 cloud_control_* 指令</li>
 * </ul>
 * <p>核实依据：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/dji-rc-plus-2/drc.html">DJI Pilot DRC 指令飞行</a>
 */
class AuthFlowHandlerTest {

    private static final String GATEWAY_SN = "rc-sn-test";
    private static final String EVENTS_TOPIC = "thing/product/" + GATEWAY_SN + "/events";
    private static final String STATE_TOPIC = "thing/product/" + GATEWAY_SN + "/state";

    private RuntimeConfig pilotRuntimeConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        when(rc.getDeviceMode()).thenReturn(DeviceMode.PILOT);
        when(rc.getControllerType()).thenReturn(RcModel.RC_PLUS_2);
        when(rc.getGatewaySn()).thenReturn(GATEWAY_SN);
        return rc;
    }

    private RuntimeConfig dockRuntimeConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        when(rc.getDeviceMode()).thenReturn(DeviceMode.DOCK);
        when(rc.getDockType()).thenReturn(DockModel.DOCK3);
        when(rc.getGatewaySn()).thenReturn("dock-sn-test");
        return rc;
    }

    /** 捕获指定 topic 的 publishJson(Object) payload 并转为 JsonNode（根节点），用于 state 消息 */
    private JsonNode capturePublished(MqttClientManager mqtt, ObjectMapper om, String topic) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(mqtt).publishJson(eq(topic), captor.capture());
        return om.valueToTree(captor.getValue());
    }

    /** 捕获指定 topic 的 publish(String) payload 并转为 JsonNode（根节点），用于 events 消息（EventEnvelope 序列化） */
    private JsonNode capturePublishedString(MqttClientManager mqtt, ObjectMapper om, String topic) throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mqtt).publish(eq(topic), captor.capture());
        return om.readTree(captor.getValue());
    }

    // ==================== TC-PILOT-013：cloud_control_auth_request 自动同意 ====================

    @Test
    @DisplayName("TC-PILOT-013: cloud_control_auth_request Pilot 模式自动同意，回 result=0+output.status=ok")
    void cloudControlAuthRequestPilotModeAutoApproves() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = Mockito.mock(DeviceState.class);
        AuthFlowHandler handler = new AuthFlowHandler(
                mqtt, state, pilotRuntimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        JsonNode data = objectMapper.readTree("""
                {"user_id":"u-001","user_callsign":"alice","control_keys":["flight"]}
                """);

        Map<String, Object> reply = handler.handle("cloud_control_auth_request", data, "bid-auth-001");

        // services_reply output：{result:0, output:{status:"ok"}}
        assertNotNull(reply);
        assertEquals(0, reply.get("result"), "result 应为 0");
        assertNotNull(reply.get("output"), "应包含 output");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) reply.get("output");
        assertEquals("ok", output.get("status"), "output.status 应为 ok");

        // events 上报 cloud_control_auth_notify（need_reply=0）
        JsonNode event = capturePublishedString(mqtt, objectMapper, EVENTS_TOPIC);
        assertEquals("cloud_control_auth_notify", event.path("method").asText());
        assertEquals(0, event.path("need_reply").asInt(), "cloud_control_auth_notify need_reply=0");
        assertEquals(0, event.path("data").path("result").asInt());
        assertEquals("ok", event.path("data").path("output").path("status").asText());
        assertEquals("bid-auth-001", event.path("bid").asText(), "events bid 应与 auth_request 一致");

        // state 上报 cloud_control_auth（非空数组，含 flight）
        JsonNode stateMsg = capturePublished(mqtt, objectMapper, STATE_TOPIC);
        JsonNode authList = stateMsg.path("data").path("cloud_control_auth");
        assertTrue(authList.isArray(), "cloud_control_auth 应为数组");
        assertFalse(authList.isEmpty(), "授权成功后 cloud_control_auth 不应为空");
        assertEquals("flight", authList.get(0).asText());
    }

    // ==================== TC-PILOT-014：cloud_control_release 清空授权 ====================

    @Test
    @DisplayName("TC-PILOT-014: cloud_control_release Pilot 模式清空授权，回 result=0+output.status=ok")
    void cloudControlReleasePilotModeClearsAuth() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = Mockito.mock(DeviceState.class);
        AuthFlowHandler handler = new AuthFlowHandler(
                mqtt, state, pilotRuntimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        // 先授权再释放，验证 state 从非空变空
        JsonNode authData = objectMapper.readTree("""
                {"user_id":"u-001","user_callsign":"alice"}
                """);
        handler.handle("cloud_control_auth_request", authData, "bid-auth-001");
        // 清除 invocations，便于后续验证 release 只调用一次 state
        Mockito.clearInvocations(mqtt);

        JsonNode releaseData = objectMapper.readTree("""
                {"control_keys":["flight"]}
                """);
        Map<String, Object> reply = handler.handle("cloud_control_release", releaseData, "bid-release-001");

        // services_reply output：{result:0, output:{status:"ok"}}
        assertNotNull(reply);
        assertEquals(0, reply.get("result"), "result 应为 0");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) reply.get("output");
        assertEquals("ok", output.get("status"), "output.status 应为 ok");

        // state 上报 cloud_control_auth（空数组）
        JsonNode stateMsg = capturePublished(mqtt, objectMapper, STATE_TOPIC);
        JsonNode authList = stateMsg.path("data").path("cloud_control_auth");
        assertTrue(authList.isArray(), "cloud_control_auth 应为数组");
        assertTrue(authList.isEmpty(), "释放后 cloud_control_auth 应为空数组");

        // release 不上报 events（无 cloud_control_auth_notify）
        verify(mqtt, never()).publish(eq(EVENTS_TOPIC), any());
    }

    // ==================== handles() 模式守卫 ====================

    @Test
    @DisplayName("handles() Dock 模式下 cloud_control_auth_request 返回 false")
    void handlesDockModeRejectsCloudControlAuth() {
        AuthFlowHandler handler = new AuthFlowHandler(
                Mockito.mock(MqttClientManager.class), Mockito.mock(DeviceState.class),
                dockRuntimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        assertFalse(handler.handles("cloud_control_auth_request"),
                "Dock 模式不应处理 cloud_control_auth_request");
        assertFalse(handler.handles("cloud_control_release"),
                "Dock 模式不应处理 cloud_control_release");
    }

    @Test
    @DisplayName("handles() Pilot 模式下 cloud_control_auth_request 返回 true")
    void handlesPilotModeAcceptsCloudControlAuth() {
        AuthFlowHandler handler = new AuthFlowHandler(
                Mockito.mock(MqttClientManager.class), Mockito.mock(DeviceState.class),
                pilotRuntimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        assertTrue(handler.handles("cloud_control_auth_request"),
                "Pilot 模式应处理 cloud_control_auth_request");
        assertTrue(handler.handles("cloud_control_release"),
                "Pilot 模式应处理 cloud_control_release");
    }

    @Test
    @DisplayName("handles() 两种模式都处理 drc_mode_enter/exit")
    void handlesBothModesAcceptDrcModeSwitch() {
        AuthFlowHandler dockHandler = new AuthFlowHandler(
                Mockito.mock(MqttClientManager.class), Mockito.mock(DeviceState.class),
                dockRuntimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());
        AuthFlowHandler pilotHandler = new AuthFlowHandler(
                Mockito.mock(MqttClientManager.class), Mockito.mock(DeviceState.class),
                pilotRuntimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        assertTrue(dockHandler.handles("drc_mode_enter"), "Dock 模式应处理 drc_mode_enter");
        assertTrue(dockHandler.handles("drc_mode_exit"), "Dock 模式应处理 drc_mode_exit");
        assertTrue(pilotHandler.handles("drc_mode_enter"), "Pilot 模式应处理 drc_mode_enter");
        assertTrue(pilotHandler.handles("drc_mode_exit"), "Pilot 模式应处理 drc_mode_exit");
    }

    // ==================== TC-PILOT-014a：drc_mode_enter ====================

    @Test
    @DisplayName("TC-PILOT-014a: drc_mode_enter Pilot 模式解析 mqtt_broker 并设 drcState=2")
    void drcModeEnterParsesMqttBrokerAndSetsDrcState() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = Mockito.mock(DeviceState.class);
        AuthFlowHandler handler = new AuthFlowHandler(
                mqtt, state, pilotRuntimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        JsonNode data = objectMapper.readTree("""
                {
                  "mqtt_broker": {
                    "address": "drc.emqx.io:8883",
                    "client_id": "drc-client-001",
                    "username": "drc-user",
                    "password": "drc-pass",
                    "enable_tls": true,
                    "expire_time": 1704038400000
                  },
                  "hsi_frequency": 5,
                  "osd_frequency": 5
                }
                """);

        Map<String, Object> reply = handler.handle("drc_mode_enter", data, "bid-drc-enter");

        assertNotNull(reply);
        assertEquals(0, reply.get("result"), "drc_mode_enter 应返回 result=0");

        // 设 drcState=2
        verify(state).setDrcState(2);

        // state 上报 drc_state=2
        JsonNode stateMsg = capturePublished(mqtt, objectMapper, STATE_TOPIC);
        assertEquals(2, stateMsg.path("data").path("drc_state").asInt());
    }

    // ==================== TC-PILOT-014b：drc_mode_exit ====================

    @Test
    @DisplayName("TC-PILOT-014b: drc_mode_exit Pilot 模式设 drcState=0")
    void drcModeExitSetsDrcStateToZero() {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = Mockito.mock(DeviceState.class);
        AuthFlowHandler handler = new AuthFlowHandler(
                mqtt, state, pilotRuntimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        Map<String, Object> reply = handler.handle("drc_mode_exit",
                objectMapper.nullNode(), "bid-drc-exit");

        assertNotNull(reply);
        assertEquals(0, reply.get("result"), "drc_mode_exit 应返回 result=0");

        // 设 drcState=0
        verify(state).setDrcState(0);

        // state 上报 drc_state=0
        JsonNode stateMsg = capturePublished(mqtt, objectMapper, STATE_TOPIC);
        assertEquals(0, stateMsg.path("data").path("drc_state").asInt());
    }
}
