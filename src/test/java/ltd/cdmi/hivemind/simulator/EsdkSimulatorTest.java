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
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.EsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.EsdkSimulator.TriggerResult;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EsdkSimulator 单元测试。
 * <p>覆盖 ESDK 互联互通：custom_data_transmission_to_esdk（Service 下行）和
 * custom_data_transmission_from_esdk（Event 上行）。</p>
 * <p>核实依据：[Dock1/Dock2/Dock3 esdk-transmit-custom-data.html]</p>
 */
class EsdkSimulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuntimeConfig runtimeConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK-SN");
        return rc;
    }

    private DiagnosticLogRecorder diagnosticRecorder() {
        return Mockito.mock(DiagnosticLogRecorder.class);
    }

    private JsonNode data(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    /** 捕获 publish 发布的事件信封（EventEnvelope 序列化字符串） */
    private JsonNode captureEnvelope(MqttClientManager mqtt) throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());
        return objectMapper.readTree(captor.getValue());
    }

    // ==================== TC-ESDK-001：custom_data_transmission_to_esdk 服务应答 ====================

    @DisplayName("TC-ESDK-001：custom_data_transmission_to_esdk 服务应答")
    @Test
    void customDataToEsdkReply() throws Exception {
        EsdkSimulator simulator = new EsdkSimulator(
                Mockito.mock(MqttClientManager.class), runtimeConfig(), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "custom_data_transmission_to_esdk",
                data("{\"value\": \"hello world\"}"));

        assertEquals(0, output.get("result"));
        assertEquals("hello world", simulator.getLastCustomData());
    }

    // ==================== TC-ESDK-002：custom_data_transmission_from_esdk 事件上报 ====================

    @DisplayName("TC-ESDK-002：custom_data_transmission_from_esdk 事件上报")
    @Test
    void customDataFromEsdkEvent() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        EsdkSimulator simulator = new EsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(), new DockTopicSchema());

        TriggerResult result = simulator.triggerCustomDataFromEsdk("hello world");

        assertTrue(result.success());

        JsonNode envelope = captureEnvelope(mqtt);
        assertEquals("custom_data_transmission_from_esdk", envelope.path("method").asText());
        assertEquals(0, envelope.path("need_reply").asInt());
        assertEquals("hello world", envelope.path("data").path("value").asText());
    }

    // ==================== TC-ESDK-003：isEsdkServiceMethod 识别 custom_data_transmission_to_esdk ====================

    @DisplayName("TC-ESDK-003：isEsdkServiceMethod 识别 custom_data_transmission_to_esdk")
    @Test
    void isEsdkServiceMethodRecognition() {
        assertTrue(EsdkSimulator.isEsdkServiceMethod("custom_data_transmission_to_esdk"));
        assertFalse(EsdkSimulator.isEsdkServiceMethod("custom_data_transmission_to_psdk"));
    }
}
