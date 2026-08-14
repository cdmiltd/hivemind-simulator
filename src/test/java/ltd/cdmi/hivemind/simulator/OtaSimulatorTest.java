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
import ltd.cdmi.hivemind.simulator.handler.OtaSimulator;
import ltd.cdmi.hivemind.simulator.handler.OtaSimulator.TriggerResult;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OtaSimulator 单元测试。
 * <p>覆盖固件升级：ota_create（Service 下行）、ota_progress（Event 上行）。</p>
 * <p>核实依据：[Dock1/Dock2/Dock3 firmware-upgrade.html]</p>
 */
class OtaSimulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuntimeConfig runtimeConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK-SN");
        Mockito.when(rc.getDroneSn()).thenReturn("DRONE-SN");
        return rc;
    }

    private JsonNode data(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @SuppressWarnings("unchecked")
    private JsonNode captureEnvelope(MqttClientManager mqtt) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.atLeastOnce()).publishJson(Mockito.anyString(), captor.capture());
        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        return objectMapper.readTree(objectMapper.writeValueAsString(envelope));
    }

    // ==================== TC-OTA-001：ota_create 服务应答 ====================

    @Test
    void otaCreateReply() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        OtaSimulator simulator = new OtaSimulator(mqtt, runtimeConfig(), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService("ota_create",
                data("{\"devices\":[{\"sn\":\"drone_sn\",\"product_version\":\"1.00.223\",\"firmware_upgrade_type\":2,"
                        + "\"file_url\":\"https://s3.com/xxx.zip\",\"md5\":\"abcdef\",\"file_size\":653467234,"
                        + "\"file_name\":\"wm245_1.00.223.zip\"}]}"));

        assertEquals(0, output.get("result"));
        assertEquals("in_progress", ((Map<?, ?>) output.get("output")).get("status"));
        assertFalse(simulator.getCurrentUpgradeDevices().isEmpty());
    }

    // ==================== TC-OTA-002：ota_create 自动模拟升级进度 ====================

    @Test
    void otaCreateAutoProgress() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        OtaSimulator simulator = new OtaSimulator(mqtt, runtimeConfig(), new DockTopicSchema());

        simulator.handleService("ota_create",
                data("{\"devices\":[{\"sn\":\"drone_sn\",\"product_version\":\"1.00.223\",\"firmware_upgrade_type\":3}]}"));

        // 等待异步进度模拟完成（3 次上报，每次间隔 2 秒，共 6 秒+）
        Thread.sleep(7000);

        // 验证至少上报了 3 次 ota_progress
        Mockito.verify(mqtt, Mockito.atLeast(3)).publishJson(Mockito.anyString(), Mockito.any());

        JsonNode envelope = captureEnvelope(mqtt);
        assertEquals("ota_progress", envelope.path("method").asText());
        assertEquals(0, envelope.path("need_reply").asInt());
    }

    // ==================== TC-OTA-003：ota_progress 事件结构 ====================

    @Test
    void otaProgressEventStructure() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        OtaSimulator simulator = new OtaSimulator(mqtt, runtimeConfig(), new DockTopicSchema());

        // 先触发 ota_create 创建升级任务
        simulator.handleService("ota_create",
                data("{\"devices\":[{\"sn\":\"drone_sn\",\"product_version\":\"1.00.223\",\"firmware_upgrade_type\":3}]}"));

        // 等待异步任务启动
        Thread.sleep(100);

        // 手动触发 ota_progress
        TriggerResult result = simulator.triggerOtaProgress("in_progress", "download_firmware", 30);
        assertTrue(result.success());

        JsonNode envelope = captureEnvelope(mqtt);
        assertEquals("ota_progress", envelope.path("method").asText());
        assertEquals(0, envelope.path("need_reply").asInt());
        assertEquals(0, envelope.path("data").path("result").asInt());
        assertEquals("in_progress", envelope.path("data").path("output").path("status").asText());
        assertEquals(30, envelope.path("data").path("output").path("progress").path("percent").asInt());
        assertEquals("download_firmware", envelope.path("data").path("output").path("progress").path("current_step").asText());
    }

    // ==================== TC-OTA-004：ota_progress current_step 枚举 ====================

    @Test
    void otaProgressCurrentStepEnum() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        OtaSimulator simulator = new OtaSimulator(mqtt, runtimeConfig(), new DockTopicSchema());

        simulator.handleService("ota_create",
                data("{\"devices\":[{\"sn\":\"drone_sn\",\"product_version\":\"1.00.223\",\"firmware_upgrade_type\":3}]}"));
        Thread.sleep(100);

        // 验证 download_firmware 步骤
        simulator.triggerOtaProgress("in_progress", "download_firmware", 50);
        JsonNode envelope1 = captureEnvelope(mqtt);
        assertEquals("download_firmware", envelope1.path("data").path("output").path("progress").path("current_step").asText());

        // 验证 upgrade_firmware 步骤
        simulator.triggerOtaProgress("in_progress", "upgrade_firmware", 80);
        JsonNode envelope2 = captureEnvelope(mqtt);
        assertEquals("upgrade_firmware", envelope2.path("data").path("output").path("progress").path("current_step").asText());
    }

    // ==================== TC-OTA-005：isOtaServiceMethod 识别固件升级指令 ====================

    @Test
    void isOtaServiceMethodRecognition() {
        assertTrue(OtaSimulator.isOtaServiceMethod("ota_create"));
        assertFalse(OtaSimulator.isOtaServiceMethod("ota_progress"));
    }
}
