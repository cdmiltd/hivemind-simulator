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
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.MediaUploader;
import ltd.cdmi.hivemind.simulator.handler.PsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.PsdkSimulator.TriggerResult;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PsdkSimulator 单元测试。
 * <p>覆盖 3 个同步 Service 指令（speaker_play_volume_set/mode_set/stop）和 4 个 Event 上报
 * （speaker_tts_play_start_progress/speaker_audio_play_start_progress/psdk_floating_window_text/
 * psdk_ui_resource_upload_result）。</p>
 * <p>核实依据：[Dock3 wayline.html] PSDK 喊话器、psdk 浮窗文本、psdk UI 资源包</p>
 */
class PsdkSimulatorTest {

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

    /** 捕获 publishJson 发布的事件信封 */
    @SuppressWarnings("unchecked")
    private JsonNode captureEnvelope(MqttClientManager mqtt) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());
        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        return objectMapper.readTree(objectMapper.writeValueAsString(envelope));
    }

    // ==================== TC-PSDK-001：speaker_play_volume_set 服务应答 ====================

    @Test
    void speakerPlayVolumeSetReply() throws Exception {
        PsdkSimulator simulator = new PsdkSimulator(
                Mockito.mock(MqttClientManager.class), runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "speaker_play_volume_set", data("{\"psdk_index\": 2, \"play_volume\": 13}"));

        assertEquals(0, output.get("result"));
        assertEquals(13, simulator.getSpeakerVolume(2));
    }

    // ==================== TC-PSDK-002：speaker_play_mode_set 服务应答 ====================

    @Test
    void speakerPlayModeSetReply() throws Exception {
        PsdkSimulator simulator = new PsdkSimulator(
                Mockito.mock(MqttClientManager.class), runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "speaker_play_mode_set", data("{\"psdk_index\": 2, \"play_mode\": 1}"));

        assertEquals(0, output.get("result"));
        assertEquals(1, simulator.getSpeakerPlayMode(2));
    }

    // ==================== TC-PSDK-003：speaker_play_stop 服务应答 ====================

    @Test
    void speakerPlayStopReply() throws Exception {
        PsdkSimulator simulator = new PsdkSimulator(
                Mockito.mock(MqttClientManager.class), runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "speaker_play_stop", data("{\"psdk_index\": 2}"));

        assertEquals(0, output.get("result"));
        assertFalse(simulator.isSpeakerPlaying(2));
    }

    // ==================== TC-PSDK-004：isPsdkServiceMethod 指令识别 ====================

    @Test
    void isPsdkServiceMethodRecognition() {
        assertTrue(PsdkSimulator.isPsdkServiceMethod("speaker_play_volume_set"));
        assertTrue(PsdkSimulator.isPsdkServiceMethod("speaker_play_mode_set"));
        assertTrue(PsdkSimulator.isPsdkServiceMethod("speaker_play_stop"));
        assertFalse(PsdkSimulator.isPsdkServiceMethod("unlock_license_switch"));
        assertFalse(PsdkSimulator.isPsdkServiceMethod("flight_areas_update"));
    }

    // ==================== TC-PSDK-005：speaker_tts_play_start_progress 事件结构 ====================

    @Test
    void ttsPlayProgressEventStructure() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        TriggerResult result = simulator.triggerTtsPlayProgress(2, "in_progress", 50, "upload", null);

        assertTrue(result.success());
        JsonNode node = captureEnvelope(mqtt);

        assertEquals("speaker_tts_play_start_progress", node.path("method").asText());
        assertEquals(0, node.path("need_reply").asInt());
        assertEquals(0, node.path("data").path("result").asInt());

        JsonNode output = node.path("data").path("output");
        assertEquals(2, output.path("psdk_index").asInt());
        assertEquals("in_progress", output.path("status").asText());
        assertEquals(50, output.path("progress").path("percent").asInt());
        assertEquals("upload", output.path("progress").path("step_key").asText());

        // md5 为内置默认 TTS 文本的 MD5（32 位 hex）
        String md5 = output.path("md5").asText();
        assertEquals(32, md5.length());
        assertTrue(md5.matches("[0-9a-f]{32}"));
        assertEquals(simulator.getDefaultTtsMd5(), md5);
    }

    // ==================== TC-PSDK-006：speaker_tts_play_start_progress status 枚举值 ====================

    @Test
    void ttsPlayProgressStatusEnum() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        // in_progress
        simulator.triggerTtsPlayProgress(2, "in_progress", 50, "upload", null);
        JsonNode node1 = captureEnvelope(mqtt);
        assertEquals("in_progress", node1.path("data").path("output").path("status").asText());

        // ok
        Mockito.clearInvocations(mqtt);
        simulator.triggerTtsPlayProgress(2, "ok", 100, "play", null);
        JsonNode node2 = captureEnvelope(mqtt);
        assertEquals("ok", node2.path("data").path("output").path("status").asText());
    }

    // ==================== TC-PSDK-007：speaker_audio_play_start_progress 事件结构 ====================

    @Test
    void audioPlayProgressEventStructure() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        TriggerResult result = simulator.triggerAudioPlayProgress(2, "in_progress", 89, "upload", null);

        assertTrue(result.success());
        JsonNode node = captureEnvelope(mqtt);

        assertEquals("speaker_audio_play_start_progress", node.path("method").asText());
        assertEquals(0, node.path("need_reply").asInt());

        JsonNode output = node.path("data").path("output");
        assertEquals(2, output.path("psdk_index").asInt());
        assertEquals("in_progress", output.path("status").asText());
        assertEquals(89, output.path("progress").path("percent").asInt());
        assertEquals("upload", output.path("progress").path("step_key").asText());

        // md5 为内置默认音频字节的 MD5（32 位 hex）
        String md5 = output.path("md5").asText();
        assertEquals(32, md5.length());
        assertEquals(simulator.getDefaultAudioMd5(), md5);
    }

    // ==================== TC-PSDK-008：psdk_floating_window_text 事件结构 ====================

    @Test
    void floatingWindowTextEventStructure() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        TriggerResult result = simulator.triggerFloatingWindowText(2, "System time : 1193683 ms");

        assertTrue(result.success());
        JsonNode node = captureEnvelope(mqtt);

        assertEquals("psdk_floating_window_text", node.path("method").asText());
        assertEquals(0, node.path("need_reply").asInt());

        // data 直接平铺 psdk_index + value（非 output 包裹）
        assertEquals(2, node.path("data").path("psdk_index").asInt());
        assertEquals("System time : 1193683 ms", node.path("data").path("value").asText());
        assertTrue(node.path("data").path("output").isMissingNode());
    }

    // ==================== TC-PSDK-009：psdk_ui_resource_upload_result 事件结构 ====================

    @Test
    void uiResourceUploadResultEventStructure() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        TriggerResult result = simulator.triggerUiResourceUploadResult(
                2, "f4a4a171/widget", 43488, 0);

        assertTrue(result.success());
        JsonNode node = captureEnvelope(mqtt);

        assertEquals("psdk_ui_resource_upload_result", node.path("method").asText());
        assertEquals(0, node.path("need_reply").asInt());

        // data 直接平铺 psdk_index/object_key/size/result（非 output 包裹）
        assertEquals(2, node.path("data").path("psdk_index").asInt());
        assertEquals("f4a4a171/widget", node.path("data").path("object_key").asText());
        assertEquals(43488, node.path("data").path("size").asLong());
        assertEquals(0, node.path("data").path("result").asInt());
        assertTrue(node.path("data").path("output").isMissingNode());
    }

    // ==================== TC-PSDK-010：MQTT 未连接拒绝 ====================

    @Test
    void rejectWhenMqttNotConnected() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(false);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        TriggerResult r1 = simulator.triggerTtsPlayProgress(2, "in_progress", 50, "upload", null);
        assertFalse(r1.success());
        assertEquals("MQTT_NOT_CONNECTED", r1.code());

        TriggerResult r2 = simulator.triggerAudioPlayProgress(2, "in_progress", 89, "upload", null);
        assertFalse(r2.success());
        assertEquals("MQTT_NOT_CONNECTED", r2.code());

        TriggerResult r3 = simulator.triggerFloatingWindowText(2, "test");
        assertFalse(r3.success());
        assertEquals("MQTT_NOT_CONNECTED", r3.code());

        TriggerResult r4 = simulator.triggerUiResourceUploadResult(2, "key", 100, 0);
        assertFalse(r4.success());
        assertEquals("MQTT_NOT_CONNECTED", r4.code());

        Mockito.verify(mqtt, Mockito.never()).publishJson(Mockito.anyString(), Mockito.any());
    }

    // ==================== TC-PSDK-011：md5 字段可由 REST API 覆盖 ====================

    @Test
    void md5OverrideByApi() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        simulator.triggerTtsPlayProgress(2, "in_progress", 50, "upload", "custom_md5_32_hex_placeholder_xx");

        JsonNode node = captureEnvelope(mqtt);
        assertEquals("custom_md5_32_hex_placeholder_xx",
                node.path("data").path("output").path("md5").asText());
    }

    // ==================== 辅助：构造函数记录 M-2 诊断日志 ====================

    @Test
    void constructorRecordsM2Diagnostic() {
        DiagnosticLogRecorder recorder = diagnosticRecorder();
        new PsdkSimulator(Mockito.mock(MqttClientManager.class), runtimeConfig(), recorder,
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        // 构造函数应记录 M-2 诊断日志（speaker_tts_play_start_progress status 枚举推断）
        Mockito.verify(recorder).record(
                Mockito.argThat(code -> "M-2".equals(code.code())),
                Mockito.eq("speaker_tts_play_start_progress"),
                Mockito.contains("success"));
    }

    // ==================== TC-PSDK-013：speaker_replay 服务应答 ====================

    @Test
    void speakerReplayReply() throws Exception {
        PsdkSimulator simulator = new PsdkSimulator(
                Mockito.mock(MqttClientManager.class), runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "speaker_replay", data("{\"psdk_index\": 2}"));

        assertEquals(0, output.get("result"));
        assertTrue(simulator.isSpeakerPlaying(2), "speaker_replay 应将播放状态置为 true");
    }

    // ==================== TC-PSDK-014：speaker_tts_play_start 服务应答 ====================

    @Test
    void speakerTtsPlayStartReply() throws Exception {
        PsdkSimulator simulator = new PsdkSimulator(
                Mockito.mock(MqttClientManager.class), runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "speaker_tts_play_start",
                data("{\"psdk_index\": 2, \"tts\": {\"name\": \"1111\", \"text\": \"1111\", \"md5\": \"0bfb9bceee974f41a6ddfd81521bd795\"}}"));

        assertEquals(0, output.get("result"));
        assertTrue(simulator.isSpeakerPlaying(2));

        Map<String, String> tts = simulator.getLastTts(2);
        assertNotNull(tts);
        assertEquals("1111", tts.get("name"));
        assertEquals("1111", tts.get("text"));
        assertEquals("0bfb9bceee974f41a6ddfd81521bd795", tts.get("md5"));
    }

    // ==================== TC-PSDK-015：speaker_audio_play_start 服务应答 ====================

    @Test
    void speakerAudioPlayStartReply() throws Exception {
        PsdkSimulator simulator = new PsdkSimulator(
                Mockito.mock(MqttClientManager.class), runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "speaker_audio_play_start",
                data("{\"psdk_index\": 2, \"file\": {\"name\": \"20230720162718\", \"url\": \"https://example.com/xxx.pcm\", \"md5\": \"b38257017001f45ec064b5157b2e4416\", \"format\": \"pcm\"}}"));

        assertEquals(0, output.get("result"));
        assertTrue(simulator.isSpeakerPlaying(2));

        Map<String, String> file = simulator.getLastAudioFile(2);
        assertNotNull(file);
        assertEquals("20230720162718", file.get("name"));
        assertEquals("https://example.com/xxx.pcm", file.get("url"));
        assertEquals("b38257017001f45ec064b5157b2e4416", file.get("md5"));
        assertEquals("pcm", file.get("format"));
    }

    // ==================== TC-PSDK-016：psdk_input_box_text_set 服务应答与浮窗事件联动 ====================

    @Test
    void inputBoxTextSetReplyAndFloatingWindowEvent() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "psdk_input_box_text_set",
                data("{\"psdk_index\": 2, \"value\": \"hello world\"}"));

        assertEquals(0, output.get("result"));
        assertEquals("hello world", simulator.getInputBoxText(2));

        // 验证自动触发 psdk_floating_window_text 事件
        JsonNode envelope = captureEnvelope(mqtt);
        assertEquals("psdk_floating_window_text", envelope.path("method").asText());
        assertEquals(0, envelope.path("need_reply").asInt());
        assertEquals(2, envelope.path("data").path("psdk_index").asInt());
        assertEquals("hello world", envelope.path("data").path("value").asText());
    }

    // ==================== TC-PSDK-017：isPsdkServiceMethod 识别第 2/3 部分新指令 ====================

    @Test
    void isPsdkServiceMethodRecognitionPart2() {
        assertTrue(PsdkSimulator.isPsdkServiceMethod("speaker_replay"));
        assertTrue(PsdkSimulator.isPsdkServiceMethod("speaker_tts_play_start"));
        assertTrue(PsdkSimulator.isPsdkServiceMethod("speaker_audio_play_start"));
        assertTrue(PsdkSimulator.isPsdkServiceMethod("psdk_input_box_text_set"));
    }

    // ==================== TC-PSDK-018：psdk_widget_value_set 服务应答 ====================

    @Test
    void widgetValueSetReply() throws Exception {
        PsdkSimulator simulator = new PsdkSimulator(
                Mockito.mock(MqttClientManager.class), runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "psdk_widget_value_set",
                data("{\"psdk_index\": 2, \"index\": 1, \"value\": 60}"));

        assertEquals(0, output.get("result"));
        assertEquals(60, simulator.getWidgetValue(2, 1));
    }

    // ==================== TC-PSDK-019：isPsdkServiceMethod 识别 psdk_widget_value_set ====================

    @Test
    void isPsdkServiceMethodRecognitionWidgetValueSet() {
        assertTrue(PsdkSimulator.isPsdkServiceMethod("psdk_widget_value_set"));
    }

    // ==================== TC-PSDK-020+021：PSDK UI 资源完整上传流程 ====================

    @Test
    void uploadUiResourceFullFlow() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);
        // mock storage_config_get (module=1) 回复含有效 STS 凭证
        Mockito.when(onlineService.sendRequest(Mockito.eq("storage_config_get"), Mockito.any()))
                .thenReturn(objectMapper.readTree(
                        "{\"data\":{\"result\":0,\"output\":{\"bucket\":\"test-bucket\",\"credentials\":{\"access_key_id\":\"ak\",\"access_key_secret\":\"sk\",\"expire\":3600,\"security_token\":\"st\"},\"endpoint\":\"https://oss.example.com\",\"object_key_prefix\":\"psdk-prefix\",\"provider\":\"ali\",\"region\":\"hz\"}}}"));

        MediaUploader mediaUploader = Mockito.mock(MediaUploader.class);
        Mockito.when(mediaUploader.upload(Mockito.any(), Mockito.any(), Mockito.anyString())).thenReturn(true);

        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                onlineService, mediaUploader, new DockTopicSchema());

        TriggerResult result = simulator.uploadUiResource(2);

        assertTrue(result.success());

        // 验证 storage_config_get 请求 module=1
        Mockito.verify(onlineService).sendRequest("storage_config_get", Map.of("module", 1));

        // 验证文件上传（object_key 格式 = prefix/psdk_index/widget）
        Mockito.verify(mediaUploader).upload(Mockito.any(), Mockito.any(), Mockito.eq("psdk-prefix/2/widget"));

        // 验证 psdk_ui_resource_upload_result 事件已发布
        JsonNode envelope = captureEnvelope(mqtt);
        assertEquals("psdk_ui_resource_upload_result", envelope.path("method").asText());
        assertEquals(2, envelope.path("data").path("psdk_index").asInt());
        assertEquals("psdk-prefix/2/widget", envelope.path("data").path("object_key").asText());
        assertEquals(0, envelope.path("data").path("result").asInt(), "上传成功 result=0");
        assertTrue(envelope.path("data").path("size").asInt() > 0, "size 应为内置资源字节数");
    }

    @Test
    void uploadUiResourceMqttNotConnected() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(false);

        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        TriggerResult result = simulator.uploadUiResource(2);

        assertFalse(result.success());
        assertEquals("MQTT_NOT_CONNECTED", result.code());
    }

    // ==================== TC-PSDK-022：custom_data_transmission_to_psdk 服务应答 ====================

    @Test
    void customDataToPsdkReply() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        Map<String, Object> output = simulator.handleService(
                "custom_data_transmission_to_psdk",
                data("{\"value\": \"hello world\"}"));

        assertEquals(0, output.get("result"));
        assertEquals("hello world", simulator.getLastCustomData());
    }

    // ==================== TC-PSDK-023：custom_data_transmission_from_psdk 事件上报 ====================

    @Test
    void customDataFromPsdkEvent() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        PsdkSimulator simulator = new PsdkSimulator(mqtt, runtimeConfig(), diagnosticRecorder(),
                Mockito.mock(DockOnlineService.class), Mockito.mock(MediaUploader.class), new DockTopicSchema());

        TriggerResult result = simulator.triggerCustomDataFromPsdk("hello world");

        assertTrue(result.success());

        JsonNode envelope = captureEnvelope(mqtt);
        assertEquals("custom_data_transmission_from_psdk", envelope.path("method").asText());
        assertEquals(0, envelope.path("need_reply").asInt());
        assertEquals("hello world", envelope.path("data").path("value").asText());
    }

    // ==================== TC-PSDK-024：isPsdkServiceMethod 识别 custom_data_transmission_to_psdk ====================

    @Test
    void isPsdkServiceMethodRecognitionCustomData() {
        assertTrue(PsdkSimulator.isPsdkServiceMethod("custom_data_transmission_to_psdk"));
    }
}
