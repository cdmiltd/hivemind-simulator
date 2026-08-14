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
import ltd.cdmi.hivemind.simulator.handler.RemoteLogSimulator;
import ltd.cdmi.hivemind.simulator.handler.RemoteLogSimulator.TriggerResult;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RemoteLogSimulator 单元测试。
 * <p>覆盖远程日志：fileupload_start（Service 下行）、fileupload_update（Service 下行）、
 * fileupload_progress（Event 上行）。</p>
 * <p>核实依据：[Dock1/Dock2/Dock3 log-upload.html]</p>
 */
class RemoteLogSimulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuntimeConfig runtimeConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK-SN");
        Mockito.when(rc.getDroneSn()).thenReturn("DRONE-SN");
        return rc;
    }

    private DiagnosticLogRecorder diagnosticRecorder() {
        return Mockito.mock(DiagnosticLogRecorder.class);
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

    // ==================== TC-RLOG-001：fileupload_start 服务应答 ====================

    @Test
    void fileUploadStartReply() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        RemoteLogSimulator simulator = new RemoteLogSimulator(mqtt, runtimeConfig(), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService("fileupload_start",
                data("{\"bucket\":\"test-bucket\",\"params\":{\"files\":[{\"module\":\"3\",\"object_key\":\"key1\",\"list\":[{\"boot_index\":1}]}]}}"));

        assertEquals(0, output.get("result"));
        assertFalse(simulator.getCurrentUploadFiles().isEmpty());
    }

    // ==================== TC-RLOG-002：fileupload_start 自动模拟上传进度 ====================

    @Test
    void fileUploadStartAutoProgress() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        RemoteLogSimulator simulator = new RemoteLogSimulator(mqtt, runtimeConfig(), diagnosticRecorder(), new DockTopicSchema());

        simulator.handleService("fileupload_start",
                data("{\"bucket\":\"test-bucket\",\"params\":{\"files\":[{\"module\":\"3\",\"object_key\":\"key1\",\"list\":[{\"boot_index\":1}]}]}}"));

        // 等待异步进度模拟完成（4 秒，2 次上报）
        Thread.sleep(5000);

        // 验证至少上报了 2 次 fileupload_progress
        Mockito.verify(mqtt, Mockito.atLeast(2)).publishJson(Mockito.anyString(), Mockito.any());

        JsonNode envelope = captureEnvelope(mqtt);
        assertEquals("fileupload_progress", envelope.path("method").asText());
        assertEquals(0, envelope.path("need_reply").asInt());
    }

    // ==================== TC-RLOG-003：fileupload_update 取消上传 ====================

    @Test
    void fileUploadUpdateCancel() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        RemoteLogSimulator simulator = new RemoteLogSimulator(mqtt, runtimeConfig(), diagnosticRecorder(), new DockTopicSchema());

        simulator.handleService("fileupload_start",
                data("{\"bucket\":\"test-bucket\",\"params\":{\"files\":[{\"module\":\"3\",\"object_key\":\"key1\",\"list\":[{\"boot_index\":1}]}]}}"));

        Map<String, Object> output = simulator.handleService("fileupload_update",
                data("{\"status\":\"cancel\",\"module_list\":[\"0\",\"3\"]}"));

        assertEquals(0, output.get("result"));
        assertTrue(simulator.getCurrentUploadFiles().isEmpty());
    }

    // ==================== TC-RLOG-004：fileupload_progress 事件结构 ====================

    @Test
    void fileUploadProgressEventStructure() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        RemoteLogSimulator simulator = new RemoteLogSimulator(mqtt, runtimeConfig(), diagnosticRecorder(), new DockTopicSchema());

        // 先触发 fileupload_start 创建文件列表（module=3 dock）
        simulator.handleService("fileupload_start",
                data("{\"bucket\":\"test-bucket\",\"params\":{\"files\":[{\"module\":\"3\",\"object_key\":\"key1\",\"list\":[{\"boot_index\":1}]}]}}"));

        // 等待异步任务启动
        Thread.sleep(100);

        // 手动触发 fileupload_progress
        TriggerResult result = simulator.triggerFileUploadProgress("ok", 100);
        assertTrue(result.success());

        JsonNode envelope = captureEnvelope(mqtt);
        assertEquals("fileupload_progress", envelope.path("method").asText());
        assertEquals(0, envelope.path("need_reply").asInt());
        assertEquals(0, envelope.path("data").path("result").asInt());
        assertEquals("ok", envelope.path("data").path("output").path("status").asText());

        // 验证文件列表结构
        JsonNode files = envelope.path("data").path("output").path("ext").path("files");
        assertTrue(files.isArray());
        assertTrue(files.size() > 0);
        JsonNode firstFile = files.get(0);
        assertTrue(firstFile.has("module"));
        assertTrue(firstFile.has("size"));
        assertTrue(firstFile.has("device_sn"));
        assertTrue(firstFile.has("key"));
        assertTrue(firstFile.has("fingerprint"));
        assertTrue(firstFile.has("progress"));

        // 验证 progress 对象完整字段（对齐 DJI Example：current_step/finish_time/progress/result/status/upload_rate）
        JsonNode progress = firstFile.path("progress");
        assertEquals(100, progress.path("progress").asInt());
        assertTrue(progress.has("current_step"));
        assertTrue(progress.has("finish_time"));
        assertTrue(progress.has("result"));
        assertEquals("ok", progress.path("status").asText());
        assertTrue(progress.has("upload_rate"));
        // dock 模块(module=3)应携带 total_step
        assertEquals("3", firstFile.path("module").asText());
        assertTrue(progress.has("total_step"));
    }

    // ==================== TC-RLOG-004b：module=0 飞行器不含 total_step ====================

    @Test
    void fileUploadProgressDroneModuleNoTotalStep() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        RemoteLogSimulator simulator = new RemoteLogSimulator(mqtt, runtimeConfig(), diagnosticRecorder(), new DockTopicSchema());

        // 触发 fileupload_start，module=0 飞行器
        simulator.handleService("fileupload_start",
                data("{\"bucket\":\"test-bucket\",\"params\":{\"files\":[{\"module\":\"0\",\"object_key\":\"key1\",\"list\":[{\"boot_index\":1}]}]}}"));

        Thread.sleep(100);

        TriggerResult result = simulator.triggerFileUploadProgress("ok", 100);
        assertTrue(result.success());

        JsonNode envelope = captureEnvelope(mqtt);
        JsonNode firstFile = envelope.path("data").path("output").path("ext").path("files").get(0);
        JsonNode progress = firstFile.path("progress");
        // 飞行器模块(module=0)不应携带 total_step，与 DJI Example 一致
        assertEquals("0", firstFile.path("module").asText());
        assertFalse(progress.has("total_step"));
    }

    // ==================== TC-RLOG-005：isRemoteLogServiceMethod 识别远程日志指令 ====================

    @Test
    void isRemoteLogServiceMethodRecognition() {
        assertTrue(RemoteLogSimulator.isRemoteLogServiceMethod("fileupload_start"));
        assertTrue(RemoteLogSimulator.isRemoteLogServiceMethod("fileupload_update"));
        assertTrue(RemoteLogSimulator.isRemoteLogServiceMethod("fileupload_list"));
        assertFalse(RemoteLogSimulator.isRemoteLogServiceMethod("fileupload_progress"));
    }

    // ==================== TC-RLOG-006：fileupload_list 服务应答 ====================

    @Test
    void fileUploadListReply() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RemoteLogSimulator simulator = new RemoteLogSimulator(mqtt, runtimeConfig(), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService("fileupload_list",
                data("{\"module_list\":[\"0\",\"3\"]}"));

        // 顶层 result=0
        assertEquals(0, output.get("result"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) output.get("files");
        assertEquals(2, files.size());

        // 验证每个文件组结构
        for (Map<String, Object> fileGroup : files) {
            assertEquals(0, fileGroup.get("result"));
            assertNotNull(fileGroup.get("device_sn"));
            assertNotNull(fileGroup.get("module"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> logList = (List<Map<String, Object>>) fileGroup.get("list");
            assertTrue(logList.size() > 0);

            // 验证 list 项字段（使用正确拼写 end_time，非 DJI Example 中的 end_ime）
            for (Map<String, Object> logFile : logList) {
                assertNotNull(logFile.get("boot_index"));
                assertNotNull(logFile.get("start_time"));
                assertNotNull(logFile.get("end_time"));
                assertFalse(logFile.containsKey("end_ime"), "不应包含 DJI Example 中的拼写错误 'end_ime'");
                assertNotNull(logFile.get("size"));
            }
        }

        // 验证 module=0 的 device_sn 为飞行器 SN，module=3 的 device_sn 为机场 SN
        Map<String, Object> droneGroup = files.stream().filter(f -> "0".equals(f.get("module"))).findFirst().orElseThrow();
        assertEquals("DRONE-SN", droneGroup.get("device_sn"));
        Map<String, Object> dockGroup = files.stream().filter(f -> "3".equals(f.get("module"))).findFirst().orElseThrow();
        assertEquals("DOCK-SN", dockGroup.get("device_sn"));
    }

    // ==================== TC-RLOG-006b：fileupload_list 模块过滤 ====================

    @Test
    void fileUploadListModuleFilter() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RemoteLogSimulator simulator = new RemoteLogSimulator(mqtt, runtimeConfig(), diagnosticRecorder(), new DockTopicSchema());

        // 仅请求 module=0（飞行器）
        Map<String, Object> output = simulator.handleService("fileupload_list",
                data("{\"module_list\":[\"0\"]}"));

        assertEquals(0, output.get("result"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) output.get("files");
        assertEquals(1, files.size(), "仅请求 module=0 时应只返回 1 个模块");
        assertEquals("0", files.get(0).get("module"));
    }
}
