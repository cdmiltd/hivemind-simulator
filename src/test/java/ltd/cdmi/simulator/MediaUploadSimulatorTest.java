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

package ltd.cdmi.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.simulator.config.SimulatorProperties;
import ltd.cdmi.simulator.device.DeviceType;
import ltd.cdmi.simulator.device.DockOnlineService;
import ltd.cdmi.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.simulator.mqtt.MqttClientManager;
import ltd.cdmi.simulator.mqtt.MqttClientManager.MqttMessageListener;
import ltd.cdmi.simulator.mqtt.TopicConstants;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 媒体管理模拟器单元测试。
 * <p>覆盖 TDD-SPEC TC-MEDIA-001~008 测试用例：
 * <ul>
 *   <li>TC-MEDIA-001：file_upload_callback 事件结构完整性</li>
 *   <li>TC-MEDIA-003：object_key 正确拼接 object_key_prefix</li>
 *   <li>TC-MEDIA-004：highest_priority_upload_flighttask_media 事件结构</li>
 *   <li>TC-MEDIA-005：upload_flighttask_media_prioritize 记录优先级</li>
 *   <li>TC-MEDIA-006：file_upload_callback 等待 events_reply 后继续</li>
 *   <li>TC-MEDIA-007：events_reply 超时不阻塞后续上传</li>
 *   <li>TC-MEDIA-008：媒体上传完整流程时序</li>
 * </ul>
 */
class MediaUploadSimulatorTest {

    private static final String DOCK_SN = "SIM-DOCK3-001";

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Device(
                        DOCK_SN, "SIM-M4D-001",
                        DeviceType.DOCK3, DeviceType.M4D,
                        "org", "code", "license"),
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000)
        );
    }

    /**
     * 可配置超时的测试子类（覆盖 eventReplyTimeoutSeconds 加速测试）。
     * <p>TC-MEDIA-007 依赖此子类验证超时不阻塞流程。</p>
     */
    private static class TestableSimulator extends MediaUploadSimulator {
        private final long timeoutSec;

        TestableSimulator(SimulatorProperties props, MqttClientManager mqtt,
                          ObjectMapper objectMapper, DockOnlineService onlineService,
                          ServiceCommandHandler commandHandler, long timeoutSec) {
            super(props, mqtt, objectMapper, onlineService, commandHandler);
            this.timeoutSec = timeoutSec;
        }

        @Override
        protected long eventReplyTimeoutSeconds() {
            return timeoutSec;
        }
    }

    /** 创建 timeout=0 的模拟器（结构测试用，不阻塞等待 events_reply） */
    private TestableSimulator createNoWaitSimulator(ObjectMapper objectMapper,
                                                     MqttClientManager mqtt,
                                                     DockOnlineService onlineService) {
        return new TestableSimulator(testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), 0);
    }

    /** Mock storage_config_get 返回含 object_key_prefix 的 requests_reply */
    private void mockStorageConfig(DockOnlineService onlineService, ObjectMapper objectMapper,
                                   String prefix) throws Exception {
        JsonNode configReply = objectMapper.readTree(String.format(
                "{\"data\":{\"output\":{\"bucket\":\"test-bucket\",\"object_key_prefix\":\"%s\"}}}", prefix));
        Mockito.when(onlineService.sendRequest(Mockito.eq("storage_config_get"), Mockito.any()))
                .thenReturn(configReply);
    }

    /** 捕获 events_reply 监听器并模拟云端回复（同步触发，测试不阻塞） */
    private void simulateEventReplies(MqttClientManager mqtt, ObjectMapper objectMapper) {
        final MqttMessageListener[] capturedListener = new MqttMessageListener[1];
        String eventsReplyTopic = TopicConstants.topic(TopicConstants.EVENTS_REPLY, DOCK_SN);

        Mockito.doAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            if (eventsReplyTopic.equals(topic)) {
                capturedListener[0] = invocation.getArgument(1);
            }
            return null;
        }).when(mqtt).addListener(Mockito.anyString(), Mockito.any(MqttMessageListener.class));

        String eventsTopic = TopicConstants.topic(TopicConstants.EVENTS, DOCK_SN);
        Mockito.doAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            Object obj = invocation.getArgument(1);
            if (eventsTopic.equals(topic) && capturedListener[0] != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = (Map<String, Object>) obj;
                String tid = (String) envelope.get("tid");
                String method = (String) envelope.get("method");
                String replyJson = String.format(
                        "{\"tid\":\"%s\",\"method\":\"%s\",\"data\":{\"result\":0}}", tid, method);
                capturedListener[0].onMessage(eventsReplyTopic, replyJson);
            }
            return null;
        }).when(mqtt).publishJson(Mockito.anyString(), Mockito.any());
    }

    // ==================== TC-MEDIA-001：file_upload_callback 事件结构 ====================

    @SuppressWarnings("unchecked")
    @Test
    void fileUploadCallbackContainsCorrectStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        MediaUploadSimulator simulator = createNoWaitSimulator(objectMapper, mqtt, onlineService);

        // 反射调用 publishFileUploadCallback（5参数：含 objectKeyPrefix）
        Method method = MediaUploadSimulator.class.getDeclaredMethod(
                "publishFileUploadCallback", String.class, String.class,
                int.class, int.class, String.class);
        method.setAccessible(true);
        method.invoke(simulator, "TEST-FLIGHT", "test.jpg", 0, 3, "prefix-abc");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        // 顶层结构
        assertEquals("file_upload_callback", node.path("method").asText());
        assertEquals(1, node.path("need_reply").asInt());
        assertEquals(DOCK_SN, node.path("gateway").asText());
        assertFalse(node.path("bid").asText().isEmpty());
        assertFalse(node.path("tid").asText().isEmpty());
        assertTrue(node.path("timestamp").asLong() > 0);

        // file 结构
        JsonNode file = node.path("data").path("file");
        assertEquals("test.jpg", file.path("name").asText());
        assertEquals("DEFAULT", file.path("cloud_to_cloud_id").asText());
        assertEquals("TEST-FLIGHT", file.path("path").asText());

        // ext 结构
        JsonNode ext = file.path("ext");
        assertEquals("TEST-FLIGHT", ext.path("flight_id").asText());
        assertEquals("0-100-0", ext.path("drone_model_key").asText());
        assertEquals("0-100-0", ext.path("payload_model_key").asText());
        assertTrue(ext.path("is_original").asBoolean());

        // metadata 结构
        JsonNode metadata = file.path("metadata");
        assertTrue(metadata.has("absolute_altitude"));
        assertTrue(metadata.has("create_time"));
        assertTrue(metadata.has("gimbal_yaw_degree"));
        assertTrue(metadata.has("relative_altitude"));
        assertTrue(metadata.path("shoot_position").has("lat"));
        assertTrue(metadata.path("shoot_position").has("lng"));

        // flight_task 结构（hivemind 据此统计上传计数）
        JsonNode flightTask = node.path("data").path("flight_task");
        assertEquals(1, flightTask.path("uploaded_file_count").asInt());
        assertEquals(3, flightTask.path("expected_file_count").asInt());
    }

    // ==================== TC-MEDIA-003：object_key 拼接 object_key_prefix ====================

    @SuppressWarnings("unchecked")
    @Test
    void objectKeyContainsPrefix() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        MediaUploadSimulator simulator = createNoWaitSimulator(objectMapper, mqtt, onlineService);

        String prefix = "abc-123";
        String flightId = "FLIGHT-001";
        String fileName = "photo.jpg";

        Method method = MediaUploadSimulator.class.getDeclaredMethod(
                "publishFileUploadCallback", String.class, String.class,
                int.class, int.class, String.class);
        method.setAccessible(true);
        method.invoke(simulator, flightId, fileName, 0, 1, prefix);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        String objectKey = node.path("data").path("file").path("object_key").asText();
        // object_key 格式：{object_key_prefix}/{flight_id}/{file_name}
        assertEquals(prefix + "/" + flightId + "/" + fileName, objectKey);
        assertTrue(objectKey.startsWith(prefix));
    }

    // ==================== TC-MEDIA-004：highest_priority_upload_flighttask_media 事件结构 ====================

    @SuppressWarnings("unchecked")
    @Test
    void highestPriorityEventStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        MediaUploadSimulator simulator = createNoWaitSimulator(objectMapper, mqtt, onlineService);

        Method method = MediaUploadSimulator.class.getDeclaredMethod(
                "publishHighestPriority", String.class);
        method.setAccessible(true);
        method.invoke(simulator, "FLIGHT-PRIORITY");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        assertEquals("highest_priority_upload_flighttask_media", node.path("method").asText());
        assertEquals(1, node.path("need_reply").asInt());
        assertEquals(DOCK_SN, node.path("gateway").asText());
        assertEquals("FLIGHT-PRIORITY", node.path("data").path("flight_id").asText());
        assertFalse(node.path("bid").asText().isEmpty());
        assertFalse(node.path("tid").asText().isEmpty());
    }

    // ==================== TC-MEDIA-005：upload_flighttask_media_prioritize 记录优先级 ====================

    @Test
    void prioritizeCommandRecordsFlightId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);

        MediaUploadSimulator simulator = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService, commandHandler, 0);

        JsonNode data = objectMapper.readTree("{\"flight_id\":\"FLIGHT-HIGH\"}");

        Map<String, Object> result = simulator.handleMediaCommand(
                "upload_flighttask_media_prioritize", data);

        assertEquals(0, result.get("result"));
        assertEquals("FLIGHT-HIGH", simulator.getPriorityFlightId());
    }

    // ==================== TC-MEDIA-006：file_upload_callback 等待 events_reply 后继续 ====================

    @Test
    void fileUploadCallbackWaitsForEventReply() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        mockStorageConfig(onlineService, objectMapper, "test-prefix");
        simulateEventReplies(mqtt, objectMapper);

        MediaUploadSimulator simulator = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), 10);

        // 执行媒体上传（应该快速完成，因为 events_reply 是同步模拟的）
        simulator.simulateMediaUpload("FLIGHT-REPLY", 3);

        // 验证所有文件都已上报（events_reply 收到后继续上传）
        List<Map<String, Object>> files = simulator.getUploadedFiles();
        assertEquals(3, files.size());
        for (Map<String, Object> f : files) {
            assertEquals("FLIGHT-REPLY", f.get("flight_id"));
        }
    }

    // ==================== TC-MEDIA-007：events_reply 超时不阻塞后续上传 ====================

    @Test
    void eventReplyTimeoutDoesNotBlockUpload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        mockStorageConfig(onlineService, objectMapper, "test-prefix");
        // 不模拟 events_reply（publishJson 默认 no-op，future 超时）

        // 1 秒超时（4 个事件 × 1 秒 = 4 秒，验证超时不阻塞）
        MediaUploadSimulator simulator = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), 1);

        simulator.simulateMediaUpload("FLIGHT-TIMEOUT", 3);

        // 验证所有文件都已上报（尽管 events_reply 超时）
        List<Map<String, Object>> files = simulator.getUploadedFiles();
        assertEquals(3, files.size());
    }

    // ==================== TC-MEDIA-008：媒体上传完整流程时序 ====================

    @Test
    void completeUploadFlowSequence() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        mockStorageConfig(onlineService, objectMapper, "seq-prefix");

        // 捕获所有 publishJson 调用的 method（用于验证时序）
        List<String> publishedMethods = new ArrayList<>();
        final MqttMessageListener[] capturedListener = new MqttMessageListener[1];
        String eventsReplyTopic = TopicConstants.topic(TopicConstants.EVENTS_REPLY, DOCK_SN);

        Mockito.doAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            if (eventsReplyTopic.equals(topic)) {
                capturedListener[0] = invocation.getArgument(1);
            }
            return null;
        }).when(mqtt).addListener(Mockito.anyString(), Mockito.any(MqttMessageListener.class));

        String eventsTopic = TopicConstants.topic(TopicConstants.EVENTS, DOCK_SN);
        Mockito.doAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            Object obj = invocation.getArgument(1);
            if (eventsTopic.equals(topic)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = (Map<String, Object>) obj;
                String method = (String) envelope.get("method");
                String tid = (String) envelope.get("tid");
                publishedMethods.add(method);
                // 模拟 events_reply
                if (capturedListener[0] != null) {
                    String replyJson = String.format(
                            "{\"tid\":\"%s\",\"method\":\"%s\",\"data\":{\"result\":0}}", tid, method);
                    capturedListener[0].onMessage(eventsReplyTopic, replyJson);
                }
            }
            return null;
        }).when(mqtt).publishJson(Mockito.anyString(), Mockito.any());

        MediaUploadSimulator simulator = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), 10);

        simulator.simulateMediaUpload("FLIGHT-SEQ", 3);

        // 验证时序：storage_config_get → highest_priority → file_upload_callback × 3
        Mockito.verify(onlineService).sendRequest(
                Mockito.eq("storage_config_get"), Mockito.any());

        assertEquals(4, publishedMethods.size());
        assertEquals("highest_priority_upload_flighttask_media", publishedMethods.get(0));
        assertEquals("file_upload_callback", publishedMethods.get(1));
        assertEquals("file_upload_callback", publishedMethods.get(2));
        assertEquals("file_upload_callback", publishedMethods.get(3));

        // 验证所有文件都已上报
        assertEquals(3, simulator.getUploadedFiles().size());
    }
}
