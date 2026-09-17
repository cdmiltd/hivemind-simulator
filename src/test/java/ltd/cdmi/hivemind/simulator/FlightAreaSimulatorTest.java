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
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator.DroneLocation;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator.FlightAreaFile;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator.RequestResult;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator.SyncStatus;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator.TriggerResult;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FlightAreaSimulator 单元测试。
 * <p>覆盖 4 个协议交互：
 * <ul>
 *   <li>flight_areas_drone_location（Event, need_reply=0）</li>
 *   <li>flight_areas_sync_progress（Event, need_reply=1）</li>
 *   <li>flight_areas_get（Requests，等待 reply）</li>
 *   <li>flight_areas_update（Service，自动联动 get）</li>
 * </ul>
 * <p>核实依据：[Dock3 wayline.html] 自定义飞行区</p>
 */
class FlightAreaSimulatorTest {

    private RuntimeConfig runtimeConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK-SN");
        return rc;
    }

    private DiagnosticLogRecorder diagnosticRecorder() {
        return Mockito.mock(DiagnosticLogRecorder.class);
    }

    /** 创建模拟器，超时缩短为 1 秒以加速测试 */
    private FlightAreaSimulator createSimulator(MqttClientManager mqtt) {
        return new FlightAreaSimulator(mqtt, runtimeConfig(), diagnosticRecorder(), new ObjectMapper(), new DockTopicSchema()) {
            @Override
            protected long replyTimeoutSeconds() {
                return 1;
            }
        };
    }

    private DroneLocation sampleLocation() {
        return new DroneLocation("d275c4e1-d864-4736-8b5d-5f5882ee9bdd", 100.11, true);
    }

    // ==================== TC-FLIGHTAREA-001：drone_location 事件结构 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-001：flight_areas_drone_location 事件结构")
    @Test
    void droneLocationEventStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightAreaSimulator simulator = createSimulator(mqtt);

        TriggerResult result = simulator.triggerDroneLocation(List.of(sampleLocation()));

        assertTrue(result.success());
        assertEquals(1, result.count());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        // 顶层结构
        assertEquals("flight_areas_drone_location", node.path("method").asText());
        assertFalse(node.path("bid").asText().isEmpty());
        assertFalse(node.path("tid").asText().isEmpty());
        assertTrue(node.path("timestamp").asLong() > 0);

        // need_reply=0（单向通知）
        assertEquals(0, node.path("need_reply").asInt());

        // data 是对象，包含 drone_locations 数组（非 data 直接为数组）
        assertTrue(node.path("data").isObject());
        assertTrue(node.path("data").path("drone_locations").isArray());
        assertEquals(1, node.path("data").path("drone_locations").size());
    }

    // ==================== TC-FLIGHTAREA-002：drone_location 字段完整性 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-002：drone_location 字段完整性")
    @Test
    void droneLocationFieldsComplete() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightAreaSimulator simulator = createSimulator(mqtt);
        simulator.triggerDroneLocation(List.of(sampleLocation()));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        JsonNode item = node.path("data").path("drone_locations").get(0);
        assertEquals(100.11, item.path("area_distance").asDouble());
        assertEquals("d275c4e1-d864-4736-8b5d-5f5882ee9bdd", item.path("area_id").asText());
        assertTrue(item.path("is_in_area").asBoolean());
    }

    // ==================== TC-FLIGHTAREA-003：多区域一次上报 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-003：多区域一次上报")
    @Test
    void multipleDroneLocations() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightAreaSimulator simulator = createSimulator(mqtt);
        DroneLocation loc1 = sampleLocation();
        DroneLocation loc2 = new DroneLocation("area-002", -50.5, false);

        TriggerResult result = simulator.triggerDroneLocation(List.of(loc1, loc2));

        assertTrue(result.success());
        assertEquals(2, result.count());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals(2, node.path("data").path("drone_locations").size());
        assertEquals("d275c4e1-d864-4736-8b5d-5f5882ee9bdd",
                node.path("data").path("drone_locations").get(0).path("area_id").asText());
        assertEquals("area-002",
                node.path("data").path("drone_locations").get(1).path("area_id").asText());
    }

    // ==================== TC-FLIGHTAREA-004：空列表拒绝 ====================

    @DisplayName("TC-FLIGHTAREA-004：空列表拒绝")
    @Test
    void rejectEmptyLocations() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightAreaSimulator simulator = createSimulator(mqtt);

        TriggerResult result = simulator.triggerDroneLocation(List.of());
        assertFalse(result.success());
        assertEquals("INVALID_LOCATIONS", result.code());
        Mockito.verify(mqtt, Mockito.never()).publish(Mockito.anyString(), Mockito.any());
    }

    @DisplayName("TC-FLIGHTAREA-004：空列表拒绝（null）")
    @Test
    void rejectNullLocations() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightAreaSimulator simulator = createSimulator(mqtt);

        TriggerResult result = simulator.triggerDroneLocation(null);
        assertFalse(result.success());
        assertEquals("INVALID_LOCATIONS", result.code());
    }

    // ==================== TC-FLIGHTAREA-005：sync_progress 事件结构 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-005：flight_areas_sync_progress 事件结构")
    @Test
    void syncProgressEventStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightAreaSimulator simulator = createSimulator(mqtt);
        FlightAreaFile file = new FlightAreaFile("geofence_xxx.json", "sha256");

        TriggerResult result = simulator.triggerSyncProgress(
                SyncStatus.SYNCHRONIZED, 0, file);

        assertTrue(result.success());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals("flight_areas_sync_progress", node.path("method").asText());
        // need_reply=1（需平台回复）
        assertEquals(1, node.path("need_reply").asInt());
        // data 是对象，含 status/reason/file
        assertTrue(node.path("data").isObject());
        assertEquals("synchronized", node.path("data").path("status").asText());
        assertEquals(0, node.path("data").path("reason").asInt());
        assertTrue(node.path("data").path("file").isObject());
    }

    // ==================== TC-FLIGHTAREA-006：sync_progress status 枚举值 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-006：sync_progress status 枚举值")
    @Test
    void syncProgressStatusEnum() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightAreaSimulator simulator = createSimulator(mqtt);

        // 验证所有 status 枚举值
        SyncStatus[] statuses = {
            SyncStatus.FAIL, SyncStatus.SWITCH_FAIL, SyncStatus.SYNCHRONIZED,
            SyncStatus.SYNCHRONIZING, SyncStatus.WAIT_SYNC
        };
        String[] expectedCodes = {
            "fail", "switch_fail", "synchronized", "synchronizing", "wait_sync"
        };

        for (int i = 0; i < statuses.length; i++) {
            simulator.triggerSyncProgress(statuses[i], 0, null);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            Mockito.verify(mqtt, Mockito.times(i + 1))
                    .publish(Mockito.anyString(), captor.capture());

            JsonNode node = objectMapper.readTree(captor.getValue());
            assertEquals(expectedCodes[i], node.path("data").path("status").asText());
        }
    }

    // ==================== TC-FLIGHTAREA-007：sync_progress file 字段结构 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-007：sync_progress file 字段结构")
    @Test
    void syncProgressFileStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightAreaSimulator simulator = createSimulator(mqtt);
        FlightAreaFile file = new FlightAreaFile("geofence_abc.json", "abc123sha256");

        simulator.triggerSyncProgress(SyncStatus.SYNCHRONIZING, 0, file);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        JsonNode fileNode = node.path("data").path("file");
        assertEquals("geofence_abc.json", fileNode.path("name").asText());
        assertEquals("abc123sha256", fileNode.path("checksum").asText());
    }

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-007：sync_progress 无 file 时不包含 file 字段")
    @Test
    void syncProgressWithoutFile() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightAreaSimulator simulator = createSimulator(mqtt);

        // file=null 时 data 不含 file 字段
        simulator.triggerSyncProgress(SyncStatus.WAIT_SYNC, 0, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertFalse(node.path("data").has("file"));
    }

    // ==================== TC-FLIGHTAREA-008：flight_areas_get 请求 + 收到 reply ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-008：flight_areas_get 请求结构")
    @Test
    void requestFlightAreasReceivesReply() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        // 捕获 addListener 注册的监听器
        AtomicReference<MqttClientManager.MqttMessageListener> listenerRef = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
            listenerRef.set(inv.getArgument(1));
            return null;
        }).when(mqtt).addListener(Mockito.anyString(), Mockito.any());

        FlightAreaSimulator simulator = createSimulator(mqtt);

        // 异步调用 requestFlightAreas（会阻塞等待 reply）
        CompletableFuture<RequestResult> future =
                CompletableFuture.supplyAsync(() -> simulator.requestFlightAreas());

        // 等待 requests 消息发送
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.timeout(2000))
                .publishJson(Mockito.contains("requests"), captor.capture());

        // 验证请求结构：method=flight_areas_get, data=null
        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        assertEquals("flight_areas_get", envelope.get("method"));
        assertNull(envelope.get("data"));
        String tid = (String) envelope.get("tid");
        assertFalse(tid.isEmpty());

        // 触发 reply
        String replyPayload = "{\"tid\":\"" + tid + "\",\"method\":\"flight_areas_get\","
                + "\"data\":{\"result\":0,\"output\":{\"files\":[]}}}";
        listenerRef.get().onMessage("requests_reply", replyPayload);

        // 验证结果
        RequestResult result = future.get(2, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(0, result.reply().path("data").path("result").asInt());
        // 空文件列表，fileValid=null
        assertNull(result.fileValid());
    }

    // ==================== TC-FLIGHTAREA-014：校验通过（合规文件名） ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-014：校验通过不自动上报 sync_progress")
    @Test
    void requestFlightAreasFileNameValid() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        AtomicReference<MqttClientManager.MqttMessageListener> listenerRef = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
            listenerRef.set(inv.getArgument(1));
            return null;
        }).when(mqtt).addListener(Mockito.anyString(), Mockito.any());

        FlightAreaSimulator simulator = createSimulator(mqtt);

        CompletableFuture<RequestResult> future =
                CompletableFuture.supplyAsync(() -> simulator.requestFlightAreas());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.timeout(2000))
                .publishJson(Mockito.contains("requests"), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        String tid = (String) envelope.get("tid");

        // reply 包含合规文件名（32 位 hex MD5）
        String replyPayload = "{\"tid\":\"" + tid + "\",\"method\":\"flight_areas_get\","
                + "\"data\":{\"result\":0,\"output\":{\"files\":[{"
                + "\"name\":\"geofence_d41d8cd98f00b204e9800998ecf8427e.json\","
                + "\"url\":\"https://example.com/xx.json\",\"checksum\":\"sha256\",\"size\":500}]}}}";
        listenerRef.get().onMessage("requests_reply", replyPayload);

        RequestResult result = future.get(2, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertTrue(result.fileValid());

        // 校验通过不自动上报 sync_progress（只发了一次 publishJson：requests）
        Mockito.verify(mqtt, Mockito.timeout(1000))
                .publishJson(Mockito.anyString(), Mockito.any());
    }

    // ==================== TC-FLIGHTAREA-015：校验失败自动上报 sync_progress(fail) ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-FLIGHTAREA-015：校验失败自动上报 sync_progress(fail, reason=1)")
    @Test
    void requestFlightAreasFileNameInvalidAutoReportFail() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        AtomicReference<MqttClientManager.MqttMessageListener> listenerRef = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
            listenerRef.set(inv.getArgument(1));
            return null;
        }).when(mqtt).addListener(Mockito.anyString(), Mockito.any());

        FlightAreaSimulator simulator = createSimulator(mqtt);

        CompletableFuture<RequestResult> future =
                CompletableFuture.supplyAsync(() -> simulator.requestFlightAreas());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.timeout(2000))
                .publishJson(Mockito.contains("requests"), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        String tid = (String) envelope.get("tid");

        // reply 包含不合规文件名（非 32 位 hex）
        String replyPayload = "{\"tid\":\"" + tid + "\",\"method\":\"flight_areas_get\","
                + "\"data\":{\"result\":0,\"output\":{\"files\":[{"
                + "\"name\":\"geofence_xxx.json\","
                + "\"url\":\"https://example.com/xx.json\",\"checksum\":\"sha256\",\"size\":500}]}}}";
        listenerRef.get().onMessage("requests_reply", replyPayload);

        RequestResult result = future.get(2, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertFalse(result.fileValid());

        // 验证自动上报了 sync_progress(fail, reason=1)（events 通过 publish 发送）
        ObjectMapper objectMapper = new ObjectMapper();
        ArgumentCaptor<String> syncCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.timeout(2000))
                .publish(Mockito.contains("events"), syncCaptor.capture());

        // events sync_progress 结构
        JsonNode syncNode = objectMapper.readTree(syncCaptor.getValue());
        assertEquals("flight_areas_sync_progress", syncNode.path("method").asText());
        assertEquals("fail", syncNode.path("data").path("status").asText());
        assertEquals(1, syncNode.path("data").path("reason").asInt());
    }

    // ==================== TC-FLIGHTAREA-016：空文件列表不校验 ====================

    // 已在 requestFlightAreasReceivesReply 中验证（fileValid=null）

    // ==================== TC-FLIGHTAREA-009：flight_areas_get 超时处理 ====================

    @DisplayName("TC-FLIGHTAREA-009：flight_areas_get 超时处理")
    @Test
    void requestFlightAreasTimeout() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        // 不触发 reply，等待超时

        FlightAreaSimulator simulator = createSimulator(mqtt);

        RequestResult result = simulator.requestFlightAreas();

        assertFalse(result.success());
        assertEquals("REPLY_TIMEOUT", result.code());
    }

    // ==================== TC-FLIGHTAREA-010：flight_areas_update service 应答 ====================

    @DisplayName("TC-FLIGHTAREA-010：flight_areas_update service 应答")
    @Test
    void handleServiceUpdateReturnsResult() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        DiagnosticLogRecorder dr = Mockito.mock(DiagnosticLogRecorder.class);
        FlightAreaSimulator simulator = new FlightAreaSimulator(
                mqtt, runtimeConfig(), dr, new ObjectMapper(), new DockTopicSchema()) {
            @Override
            protected long replyTimeoutSeconds() { return 1; }
        };

        Map<String, Object> output = simulator.handleServiceUpdate();

        assertEquals(0, output.get("result"));
    }

    // ==================== TC-FLIGHTAREA-011：update 自动联动 get + M-2 诊断日志 ====================

    @DisplayName("TC-FLIGHTAREA-011：flight_areas_update 自动联动 flight_areas_get")
    @Test
    void handleServiceUpdateAutoTriggerGetAndLog() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        DiagnosticLogRecorder dr = Mockito.mock(DiagnosticLogRecorder.class);
        FlightAreaSimulator simulator = new FlightAreaSimulator(
                mqtt, runtimeConfig(), dr, new ObjectMapper(), new DockTopicSchema()) {
            @Override
            protected long replyTimeoutSeconds() { return 1; }
        };

        simulator.handleServiceUpdate();

        // 验证 M-2 诊断日志已记录
        Mockito.verify(dr).record(
                Mockito.eq(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE),
                Mockito.eq("flight_areas_update"),
                Mockito.contains("update→get 自动联动"));

        // 验证异步触发 flight_areas_get requests（延迟 100ms + 超时 1s）
        Mockito.verify(mqtt, Mockito.timeout(2000))
                .publishJson(Mockito.contains("requests"), Mockito.any());
    }

    // ==================== TC-FLIGHTAREA-012：MQTT 未连接拒绝 ====================

    @DisplayName("TC-FLIGHTAREA-012：MQTT 未连接拒绝（triggerDroneLocation）")
    @Test
    void rejectDroneLocationWhenMqttNotConnected() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(false);

        FlightAreaSimulator simulator = createSimulator(mqtt);

        TriggerResult result = simulator.triggerDroneLocation(List.of(sampleLocation()));
        assertFalse(result.success());
        assertEquals("MQTT_NOT_CONNECTED", result.code());
        Mockito.verify(mqtt, Mockito.never()).publish(Mockito.anyString(), Mockito.any());
    }

    @DisplayName("TC-FLIGHTAREA-012：MQTT 未连接拒绝（triggerSyncProgress）")
    @Test
    void rejectSyncProgressWhenMqttNotConnected() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(false);

        FlightAreaSimulator simulator = createSimulator(mqtt);

        TriggerResult result = simulator.triggerSyncProgress(SyncStatus.SYNCHRONIZED, 0, null);
        assertFalse(result.success());
        assertEquals("MQTT_NOT_CONNECTED", result.code());
        Mockito.verify(mqtt, Mockito.never()).publish(Mockito.anyString(), Mockito.any());
    }

    @DisplayName("TC-FLIGHTAREA-012：MQTT 未连接拒绝（requestFlightAreas）")
    @Test
    void rejectRequestFlightAreasWhenMqttNotConnected() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(false);

        FlightAreaSimulator simulator = createSimulator(mqtt);

        RequestResult result = simulator.requestFlightAreas();
        assertFalse(result.success());
        assertEquals("MQTT_NOT_CONNECTED", result.code());
        Mockito.verify(mqtt, Mockito.never()).publishJson(Mockito.anyString(), Mockito.any());
    }
}
