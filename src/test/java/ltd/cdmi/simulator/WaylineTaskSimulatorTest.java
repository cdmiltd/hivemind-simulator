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
import ltd.cdmi.simulator.config.RuntimeConfig;
import ltd.cdmi.simulator.config.SimulatorProperties;
import ltd.cdmi.simulator.device.DeviceState;
import ltd.cdmi.simulator.device.DeviceType;
import ltd.cdmi.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WaylineTaskSimulator 单元测试。
 * <p>覆盖：
 * <ul>
 *   <li>flighttask_progress 事件结构（in_progress/ok）</li>
 *   <li>Dock 类型归属校验（TC-WAYLINE-013/014/015）</li>
 * </ul>
 */
class WaylineTaskSimulatorTest {

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Device(
                        "SIM-DOCK3-001", "SIM-M4D-001",
                        DeviceType.DOCK3, DeviceType.M4D,
                        "org", "code", "license"),
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000)
        );
    }

    /** 创建指定 Dock 类型的 RuntimeConfig mock */
    private RuntimeConfig runtimeConfig(DeviceType dockType) {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockType()).thenReturn(dockType);
        return rc;
    }

    /** 创建 DiagnosticLogRecorder mock */
    private DiagnosticLogRecorder diagnosticRecorder() {
        return Mockito.mock(DiagnosticLogRecorder.class);
    }

    /** 反射调用 handleWaylineCommand（私有方法） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeCommand(WaylineTaskSimulator simulator, String method, JsonNode data)
            throws Exception {
        Method m = WaylineTaskSimulator.class.getDeclaredMethod(
                "handleWaylineCommand", String.class, JsonNode.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(simulator, method, data);
    }

    // ==================== flighttask_progress 事件结构 ====================

    @SuppressWarnings("unchecked")
    @Test
    void publishProgressContainsCorrectStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK3), diagnosticRecorder());

        // 用反射设置当前任务 ID
        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "TEST-FLIGHT-001");

        Field trackIdField = WaylineTaskSimulator.class.getDeclaredField("currentTrackId");
        trackIdField.setAccessible(true);
        trackIdField.set(simulator, "TEST-TRACK-001");

        // stepIndex=2 → current_step=STEP_SEQUENCE[2]=25, percent=60（传入参数）
        simulator.publishProgress("in_progress", 2, 60);

        // 捕获 mqtt.publishJson 的参数
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        String json = objectMapper.writeValueAsString(envelope);
        JsonNode node = objectMapper.readTree(json);

        // 顶层结构
        assertEquals("flighttask_progress", node.path("method").asText());
        assertFalse(node.path("bid").asText().isEmpty());
        assertFalse(node.path("tid").asText().isEmpty());
        assertTrue(node.path("timestamp").asLong() > 0);

        // data.result = 0
        assertEquals(0, node.path("data").path("result").asInt());

        // output 结构
        JsonNode output = node.path("data").path("output");
        assertEquals("in_progress", output.path("status").asText());

        // progress 结构
        JsonNode progress = output.path("progress");
        assertEquals(25, progress.path("current_step").asInt());
        assertEquals(60, progress.path("percent").asInt());

        // ext 结构
        JsonNode ext = output.path("ext");
        assertEquals("TEST-FLIGHT-001", ext.path("flight_id").asText());
        assertEquals("TEST-TRACK-001", ext.path("track_id").asText());
        assertTrue(ext.has("current_waypoint_index"));
        assertTrue(ext.has("media_count"));
        assertTrue(ext.has("wayline_id"));
        assertTrue(ext.has("wayline_mission_state"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishProgressWithOkStatus() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK3), diagnosticRecorder());

        // stepIndex=5 → current_step=STEP_SEQUENCE[5]=35（最后一步）
        simulator.publishProgress("ok", 5, 100);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        assertEquals("ok", node.path("data").path("output").path("status").asText());
        assertEquals(35, node.path("data").path("output").path("progress").path("current_step").asInt());
        assertEquals(100, node.path("data").path("output").path("progress").path("percent").asInt());
    }

    // ==================== TC-WAYLINE-013：flighttask_stop 仅 Dock2/3 支持 ====================

    @Test
    void flighttaskStopRejectedOnDock1() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK1), diagnosticRecorder());

        Map<String, Object> result = invokeCommand(simulator, "flighttask_stop", null);

        // Dock1 不支持 flighttask_stop，返回 result=1
        assertEquals(1, result.get("result"));
    }

    @Test
    void flighttaskStopAcceptedOnDock3() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK3), diagnosticRecorder());

        Map<String, Object> result = invokeCommand(simulator, "flighttask_stop", null);

        // Dock3 支持 flighttask_stop，返回 result=0
        assertEquals(0, result.get("result"));
    }

    // ==================== TC-WAYLINE-014：return_specific_home 仅 Dock2/3 支持 ====================

    @Test
    void returnSpecificHomeRejectedOnDock1() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK1), diagnosticRecorder());

        Map<String, Object> result = invokeCommand(simulator, "return_specific_home", null);

        // Dock1 不支持 return_specific_home，返回 result=1
        assertEquals(1, result.get("result"));
    }

    @Test
    void returnSpecificHomeAcceptedOnDock2() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK2), diagnosticRecorder());

        Map<String, Object> result = invokeCommand(simulator, "return_specific_home", null);

        // Dock2 支持 return_specific_home，返回 result=0
        assertEquals(0, result.get("result"));
    }

    // ==================== TC-WAYLINE-015：flight_setup_abort 仅 Dock1 支持 ====================

    @Test
    void flightSetupAbortAcceptedOnDock1() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK1), diagnosticRecorder());

        Map<String, Object> result = invokeCommand(simulator, "flight_setup_abort", null);

        // Dock1 支持 flight_setup_abort，返回 result=0
        assertEquals(0, result.get("result"));
    }

    @Test
    void flightSetupAbortRejectedOnDock2() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK2), diagnosticRecorder());

        Map<String, Object> result = invokeCommand(simulator, "flight_setup_abort", null);

        // Dock2 不支持 flight_setup_abort，返回 result=1
        assertEquals(1, result.get("result"));
    }

    @Test
    void flightSetupAbortRejectedOnDock3() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK3), diagnosticRecorder());

        Map<String, Object> result = invokeCommand(simulator, "flight_setup_abort", null);

        // Dock3 不支持 flight_setup_abort，返回 result=1
        assertEquals(1, result.get("result"));
    }

    // ==================== 通用命令不受 Dock 类型限制 ====================

    @Test
    void commonCommandsAcceptedOnAllDocks() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        // flighttask_undo 在所有 Dock 上都支持
        for (DeviceType dockType : new DeviceType[]{DeviceType.DOCK1, DeviceType.DOCK2, DeviceType.DOCK3}) {
            WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                    testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                    runtimeConfig(dockType), diagnosticRecorder());

            Map<String, Object> result = invokeCommand(simulator, "flighttask_undo", null);
            assertEquals(0, result.get("result"),
                    "flighttask_undo 应在 " + dockType.getShortName() + " 上返回 result=0");
        }
    }
}
