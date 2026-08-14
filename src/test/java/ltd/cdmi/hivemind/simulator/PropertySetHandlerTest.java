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

package ltd.cdmi.hivemind.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.PropertySetHandler;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager.MqttMessageListener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * property/set_reply 格式测试（TC-PROP-001/006）。
 * <p>验证 set_reply 中每个被设置的叶子字段用 {"result": 0} 替换原值，对齐 DJI 文档示例。</p>
 */
class PropertySetHandlerTest {

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null);
    }

    private RuntimeConfig testRuntimeConfig() {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
    }

    /** 构造 PropertySetHandler 并触发一次 property/set 回调，返回捕获的 set_reply JSON */
    private JsonNode firePropertySet(String setDataJson, DeviceState state) throws Exception {
        return firePropertySet(setDataJson, state, DeviceType.DOCK3);
    }

    /** 构造指定 Dock 版本的 PropertySetHandler 并触发一次 property/set 回调 */
    private JsonNode firePropertySet(String setDataJson, DeviceState state, DeviceType dockType) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diag = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder cov = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = testRuntimeConfig();
        rc.setDockType(dockType);

        PropertySetHandler handler = new PropertySetHandler(
                testProps(), mqtt, state, objectMapper, diag, cov, rc, new DockTopicSchema());
        handler.init();

        // 捕获 addListener 注册的回调
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MqttMessageListener> listenerCaptor = ArgumentCaptor.forClass(MqttMessageListener.class);
        Mockito.verify(mqtt).addListener(Mockito.anyString(), listenerCaptor.capture());
        MqttMessageListener listener = listenerCaptor.getValue();

        // 构造 property/set 消息
        String tid = "test-tid-001";
        String bid = "test-bid-001";
        Map<String, Object> setMsg = new LinkedHashMap<>();
        setMsg.put("tid", tid);
        setMsg.put("bid", bid);
        setMsg.put("timestamp", System.currentTimeMillis());
        setMsg.put("data", objectMapper.readTree(setDataJson));
        String payload = objectMapper.writeValueAsString(setMsg);

        // 触发回调
        listener.onMessage("thing/product/dock-sn/property/set", payload);

        // 捕获 publishJson 发布的 set_reply
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> objCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(topicCaptor.capture(), objCaptor.capture());

        assertTrue(topicCaptor.getValue().endsWith("/property/set_reply"), "应回复到 property/set_reply topic");
        return objectMapper.valueToTree(objCaptor.getValue());
    }

    @Test
    void scalarPropertySetReplyHasResultWrapper() throws Exception {
        // TC-PROP-001：标量属性 set_reply 格式
        JsonNode reply = firePropertySet("{\"silent_mode\": 1}", new DeviceState());
        JsonNode data = reply.path("data");
        assertTrue(data.has("silent_mode"), "set_reply data 应包含 silent_mode");
        assertEquals(0, data.path("silent_mode").path("result").asInt(),
                "标量属性 set_reply 应为 {\"silent_mode\": {\"result\": 0}}");
    }

    @Test
    void structPropertySetReplyHasNestedResultWrapper() throws Exception {
        // TC-PROP-006：struct 属性 set_reply 格式（对齐 DJI 文档示例）
        JsonNode reply = firePropertySet("{\"distance_limit_status\": {\"state\": 1}}", new DeviceState());
        JsonNode data = reply.path("data");
        assertTrue(data.has("distance_limit_status"), "set_reply data 应包含 distance_limit_status");
        assertEquals(0, data.path("distance_limit_status").path("state").path("result").asInt(),
                "struct 属性 set_reply 应为 {\"distance_limit_status\": {\"state\": {\"result\": 0}}}");
    }

    @Test
    void setReplyEnvelopeHasTidBidTimestamp() throws Exception {
        // set_reply envelope 必须回显 tid/bid/timestamp
        JsonNode reply = firePropertySet("{\"silent_mode\": 0}", new DeviceState());
        assertEquals("test-tid-001", reply.path("tid").asText(), "set_reply 应回显 tid");
        assertEquals("test-bid-001", reply.path("bid").asText(), "set_reply 应回显 bid");
        assertTrue(reply.has("timestamp"), "set_reply 应包含 timestamp");
    }

    @Test
    void airTransferEnablePropertySetUpdatesState() throws Exception {
        // TC-PROP-007：air_transfer_enable（rw）状态更新
        DeviceState state = new DeviceState();
        assertTrue(state.isAirTransferEnable(), "默认应为 true（空中回传开启）");
        firePropertySet("{\"air_transfer_enable\": false}", state);
        assertFalse(state.isAirTransferEnable(), "property/set 后应更新为 false");
    }

    @Test
    void userExperienceImprovementPropertySetUpdatesState() throws Exception {
        // TC-PROP-008：user_experience_improvement（rw）状态更新
        DeviceState state = new DeviceState();
        assertEquals(0, state.getUserExperienceImprovement(), "默认应为 0（初始状态）");
        firePropertySet("{\"user_experience_improvement\": 2}", state);
        assertEquals(2, state.getUserExperienceImprovement(), "property/set 后应更新为 2（同意加入）");
    }

    @Test
    void silentModePropertySetUpdatesState() throws Exception {
        // TC-PROP-001 补充：silent_mode（rw）状态更新
        DeviceState state = new DeviceState();
        assertEquals(0, state.getSilentMode(), "默认应为 0（非静音）");
        firePropertySet("{\"silent_mode\": 1}", state);
        assertEquals(1, state.getSilentMode(), "property/set 后应更新为 1（静音模式）");
    }

    @Test
    void dock1AirTransferEnableSetDoesNotUpdateState() throws Exception {
        // TC-PROP-007 Dock1 特例：Dock1 不支持 air_transfer_enable，收到 set 不更新状态
        DeviceState state = new DeviceState();
        assertTrue(state.isAirTransferEnable(), "默认应为 true");
        JsonNode reply = firePropertySet("{\"air_transfer_enable\": false}", state, DeviceType.DOCK1);
        assertTrue(state.isAirTransferEnable(), "Dock1 不支持 air_transfer_enable，状态不应更新");
        // 仍回复 set_reply（result=0）
        assertEquals(0, reply.path("data").path("air_transfer_enable").path("result").asInt(),
                "Dock1 仍应回复 set_reply result=0");
    }
}
