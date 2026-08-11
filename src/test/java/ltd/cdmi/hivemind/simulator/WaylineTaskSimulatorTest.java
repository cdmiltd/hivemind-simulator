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
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
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
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", ""),
                new SimulatorProperties.Media("")
        );
    }

    /** 创建指定 Dock 类型的 RuntimeConfig mock */
    private RuntimeConfig runtimeConfig(DeviceType dockType) {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockType()).thenReturn(dockType);
        Mockito.when(rc.getLocationLatitude()).thenReturn(30.67);
        Mockito.when(rc.getLocationLongitude()).thenReturn(104.07);
        Mockito.when(rc.getLocationHeight()).thenReturn(500.0);
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

    /**
     * TC-WAYLINE-022：飞行中取消任务返回 326109 拒绝。
     * <p>飞行器在飞行中（mode_code ∈ {3-12}）时，取消任务应返回 result=326109
     *（因飞行器已经起飞，不支持取消，可通过返航按钮取消），
     * 不重置位置、不修改 mode_code、不上报 canceled progress。
     * <p>核实依据：[Dock1 wayline.html 取消准备中的任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)
     * 返回码 326109 原文</p>
     */
    @Test
    void flighttaskStopInFlightReturns326109() throws Exception {
        // TC-WAYLINE-022: 飞行中（mode_code ∈ {3-12}）取消任务返回 326109
        int[] flightModes = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        for (int modeCode : flightModes) {
            ObjectMapper objectMapper = new ObjectMapper();
            DeviceState state = new DeviceState();
            state.setDroneModeCode(modeCode);
            MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
            ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
            MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

            WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                    testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                    runtimeConfig(DeviceType.DOCK3), diagnosticRecorder());

            Map<String, Object> result = invokeCommand(simulator, "flighttask_stop", null);

            assertEquals(326109, result.get("result"),
                    "飞行中（mode_code=" + modeCode + "）取消任务应返回 326109");
            assertEquals(modeCode, state.getDroneModeCode(),
                    "飞行中取消不应修改 droneModeCode（mode_code=" + modeCode + "）");
            Mockito.verifyNoInteractions(mqtt);
        }
    }

    /**
     * TC-WAYLINE-023：异常态取消任务返回 326108 拒绝。
     * <p>飞行器在异常态（mode_code=13 升级中 / mode_code=14 未连接）时，取消任务应返回 result=326108
     *（当前状态不支持），不重置位置、不修改 mode_code、不上报 canceled progress。
     * <p>核实依据：[Dock1 wayline.html 取消准备中的任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)
     * 返回码 326108 原文</p>
     */
    @Test
    void flighttaskStopAbnormalStateReturns326108() throws Exception {
        // TC-WAYLINE-023: 异常态（mode_code ∈ {13, 14}）取消任务返回 326108
        int[] abnormalModes = {13, 14};
        for (int modeCode : abnormalModes) {
            ObjectMapper objectMapper = new ObjectMapper();
            DeviceState state = new DeviceState();
            state.setDroneModeCode(modeCode);
            MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
            ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
            MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

            WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                    testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                    runtimeConfig(DeviceType.DOCK3), diagnosticRecorder());

            Map<String, Object> result = invokeCommand(simulator, "flighttask_stop", null);

            assertEquals(326108, result.get("result"),
                    "异常态（mode_code=" + modeCode + "）取消任务应返回 326108");
            assertEquals(modeCode, state.getDroneModeCode(),
                    "异常态取消不应修改 droneModeCode（mode_code=" + modeCode + "）");
            Mockito.verifyNoInteractions(mqtt);
        }
    }

    // ==================== TC-LOC-004：return_home_info 使用机场位置 ====================

    /**
     * return_home_info 事件的 planned_path_points 取自 runtimeConfig.getLocation*()，
     * 而非 props.location()（yml 静态配置）。
     */
    @SuppressWarnings("unchecked")
    @Test
    void returnHomeInfoUsesRuntimeConfigLocation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        // 使用自定义位置（非 yml 默认值）验证取自 runtimeConfig
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockType()).thenReturn(DeviceType.DOCK3);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK3-SN");
        Mockito.when(rc.getLocationLatitude()).thenReturn(31.23);
        Mockito.when(rc.getLocationLongitude()).thenReturn(121.47);
        Mockito.when(rc.getLocationHeight()).thenReturn(10.0);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                rc, diagnosticRecorder());

        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "FLIGHT-HOME-001");

        Method m = WaylineTaskSimulator.class.getDeclaredMethod("publishReturnHomeInfo");
        m.setAccessible(true);
        m.invoke(simulator);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), captor.capture());

        Map<String, Object> envelope = (Map<String, Object>) captor.getValue();
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        assertEquals("return_home_info", node.path("method").asText());
        JsonNode pathPoint = node.path("data").path("planned_path_points").get(0);
        assertEquals(31.23, pathPoint.path("latitude").asDouble());
        assertEquals(121.47, pathPoint.path("longitude").asDouble());
        assertEquals(10.0, pathPoint.path("height").asDouble());
        assertEquals("FLIGHT-HOME-001", node.path("data").path("flight_id").asText());
    }

    // ==================== TC-LOC-005：无人机位置随飞行步骤更新 ====================

    /**
     * 各执行步骤的无人机位置更新策略：
     * case 24（起飞）→ 机场位置, height=0
     * case 25（航线执行中）→ 机场+偏移, height=50
     * case 27（降落机场）→ 机场位置, height=20
     * case 28（关盖）→ 机场位置, height=0
     */
    @Test
    void dronePositionUpdatesByFlightStep() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK3), diagnosticRecorder());

        Method m = WaylineTaskSimulator.class.getDeclaredMethod("updateDroneStateByStep", int.class);
        m.setAccessible(true);

        // case 24（起飞）
        m.invoke(simulator, 24);
        assertEquals(30.67, state.getDroneLatitude());
        assertEquals(104.07, state.getDroneLongitude());
        assertEquals(0.0, state.getDroneHeight());
        assertEquals(4, state.getDroneModeCode());

        // case 25（航线执行中）
        m.invoke(simulator, 25);
        assertEquals(30.67 + 0.001, state.getDroneLatitude());
        assertEquals(104.07 + 0.001, state.getDroneLongitude());
        assertEquals(50.0, state.getDroneHeight());
        assertEquals(5, state.getDroneModeCode());

        // case 27（降落机场）
        m.invoke(simulator, 27);
        assertEquals(30.67, state.getDroneLatitude());
        assertEquals(104.07, state.getDroneLongitude());
        assertEquals(20.0, state.getDroneHeight());
        assertEquals(9, state.getDroneModeCode());

        // case 28（关盖）
        m.invoke(simulator, 28);
        assertEquals(30.67, state.getDroneLatitude());
        assertEquals(104.07, state.getDroneLongitude());
        assertEquals(0.0, state.getDroneHeight());
        assertEquals(10, state.getDroneModeCode());
    }

    // ==================== TC-LOC-006：任务完成后无人机位置重置 ====================

    /**
     * 任务完成（completeTask）后无人机位置重置为机场位置，避免前端显示残留飞行偏移。
     */
    @Test
    void dronePositionResetsOnTaskComplete() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        // 模拟飞行中位置（偏移状态）
        state.setDroneLatitude(31.0);
        state.setDroneLongitude(122.0);
        state.setDroneHeight(80.0);
        state.setDroneModeCode(5);

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DeviceType.DOCK3), diagnosticRecorder());

        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "FLIGHT-RESET-001");

        Method m = WaylineTaskSimulator.class.getDeclaredMethod("completeTask");
        m.setAccessible(true);
        m.invoke(simulator);

        // 无人机归舱后位置应重置为机场位置
        assertEquals(30.67, state.getDroneLatitude());
        assertEquals(104.07, state.getDroneLongitude());
        assertEquals(0.0, state.getDroneHeight());
        assertEquals(0, state.getDroneModeCode());
        assertTrue(state.isDroneInDock());
    }
}
