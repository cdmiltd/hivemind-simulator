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
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.handler.AirSenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.AirSenseSimulator.AirSenseAlert;
import ltd.cdmi.hivemind.simulator.handler.AirSenseSimulator.TriggerResult;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AirSenseSimulator 单元测试。
 * <p>覆盖：
 * <ul>
 *   <li>airsense_warning 事件结构（data 为数组、need_reply=1）</li>
 *   <li>字段完整性（icao/warning_level/latitude 等 10 个字段）</li>
 *   <li>空列表拒绝、MQTT 未连接拒绝</li>
 * </ul>
 * <p>核实依据：[Dock1 wayline.html] Event airsense_warning</p>
 */
class AirSenseSimulatorTest {

    private RuntimeConfig runtimeConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK-SN");
        return rc;
    }

    private AirSenseAlert sampleAlert() {
        return new AirSenseAlert(
                "B-5931", 3, 12.23, 12.23, 100,
                1, 89.1, 80, 0, 100);
    }

    // ==================== 事件结构验证 ====================

    @SuppressWarnings("unchecked")
    @Test
    void airsenseWarningEventStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        AirSenseSimulator simulator = new AirSenseSimulator(mqtt, runtimeConfig(), new DockTopicSchema());

        TriggerResult result = simulator.trigger(List.of(sampleAlert()));

        assertTrue(result.success());
        assertEquals(1, result.count());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        // 顶层结构
        assertEquals("airsense_warning", node.path("method").asText());
        assertFalse(node.path("bid").asText().isEmpty());
        assertFalse(node.path("tid").asText().isEmpty());
        assertTrue(node.path("timestamp").asLong() > 0);

        // need_reply=1（AirSense 需平台回复）
        assertEquals(1, node.path("need_reply").asInt());

        // data 直接是数组（非对象包裹）
        assertTrue(node.path("data").isArray());
        assertEquals(1, node.path("data").size());
    }

    // ==================== 字段完整性验证 ====================

    @SuppressWarnings("unchecked")
    @Test
    void allFieldsPresentAndCorrect() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        AirSenseSimulator simulator = new AirSenseSimulator(mqtt, runtimeConfig(), new DockTopicSchema());
        simulator.trigger(List.of(sampleAlert()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        JsonNode item = node.path("data").get(0);
        assertEquals("B-5931", item.path("icao").asText());
        assertEquals(3, item.path("warning_level").asInt());
        assertEquals(12.23, item.path("latitude").asDouble());
        assertEquals(12.23, item.path("longitude").asDouble());
        assertEquals(100, item.path("altitude").asInt());
        assertEquals(1, item.path("altitude_type").asInt());
        assertEquals(89.1, item.path("heading").asDouble());
        assertEquals(80, item.path("relative_altitude").asInt());
        assertEquals(0, item.path("vert_trend").asInt());
        assertEquals(100, item.path("distance").asInt());
    }

    // ==================== 多航班支持 ====================

    @SuppressWarnings("unchecked")
    @Test
    void multipleAlertsInOneEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        AirSenseSimulator simulator = new AirSenseSimulator(mqtt, runtimeConfig(), new DockTopicSchema());
        AirSenseAlert alert1 = sampleAlert();
        AirSenseAlert alert2 = new AirSenseAlert(
                "B-7372", 4, 30.67, 104.07, 500,
                0, 180.0, 200, 1, 300);

        TriggerResult result = simulator.trigger(List.of(alert1, alert2));

        assertTrue(result.success());
        assertEquals(2, result.count());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        assertEquals(2, node.path("data").size());
        assertEquals("B-5931", node.path("data").get(0).path("icao").asText());
        assertEquals("B-7372", node.path("data").get(1).path("icao").asText());
    }

    // ==================== 拒绝场景 ====================

    @Test
    void rejectEmptyAlerts() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        AirSenseSimulator simulator = new AirSenseSimulator(mqtt, runtimeConfig(), new DockTopicSchema());

        TriggerResult result = simulator.trigger(List.of());
        assertFalse(result.success());
        assertEquals("INVALID_ALERTS", result.code());
        Mockito.verify(mqtt, Mockito.never()).publishJson(Mockito.anyString(), Mockito.any());
    }

    @Test
    void rejectNullAlerts() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        AirSenseSimulator simulator = new AirSenseSimulator(mqtt, runtimeConfig(), new DockTopicSchema());

        TriggerResult result = simulator.trigger(null);
        assertFalse(result.success());
        assertEquals("INVALID_ALERTS", result.code());
    }

    @Test
    void rejectWhenMqttNotConnected() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(false);

        AirSenseSimulator simulator = new AirSenseSimulator(mqtt, runtimeConfig(), new DockTopicSchema());

        TriggerResult result = simulator.trigger(List.of(sampleAlert()));
        assertFalse(result.success());
        assertEquals("MQTT_NOT_CONNECTED", result.code());
        Mockito.verify(mqtt, Mockito.never()).publishJson(Mockito.anyString(), Mockito.any());
    }
}
