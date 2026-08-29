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
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
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
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null,
                null,
                null
        );
    }

    /** 创建指定 Dock 类型的 RuntimeConfig mock */
    private RuntimeConfig runtimeConfig(DockModel dockType) {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockType()).thenReturn(dockType);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK3-SN");
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
    @DisplayName("TC-WAYLINE-006：flighttask_progress 事件结构")
    @Test
    void publishProgressContainsCorrectStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        // 用反射设置当前任务 ID
        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "TEST-FLIGHT-001");

        Field trackIdField = WaylineTaskSimulator.class.getDeclaredField("currentTrackId");
        trackIdField.setAccessible(true);
        trackIdField.set(simulator, "TEST-TRACK-001");

        // stepIndex=2 → Dock3 current_step=stepSequence()[2]=26, percent=60（传入参数）
        simulator.publishProgress("in_progress", 2, 60);

        // 捕获 mqtt.publish 的参数
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

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
        assertEquals(26, progress.path("current_step").asInt()); // Dock3 stepSequence[2]=26
        assertEquals(60, progress.path("percent").asInt());

        // ext 结构
        JsonNode ext = output.path("ext");
        assertEquals("TEST-FLIGHT-001", ext.path("flight_id").asText());
        assertEquals("TEST-TRACK-001", ext.path("track_id").asText());
        assertTrue(ext.has("current_waypoint_index"));
        assertTrue(ext.has("media_count"));
        assertTrue(ext.has("wayline_id"));
        assertTrue(ext.has("wayline_mission_state"));

        // output 中不应有 flight_id（DJI 文档 output 只有 ext/status/progress）
        assertFalse(output.has("flight_id"));
        // in_progress 状态不应有 break_point
        assertFalse(ext.has("break_point"));
    }

    @SuppressWarnings("unchecked")
    @DisplayName("补充测试：break_point 结构（paused 状态触发）")
    @Test
    void publishProgressWithBreakPoint() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        // paused 状态应触发 break_point
        simulator.publishProgress("paused", 2, 60);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        JsonNode ext = node.path("data").path("output").path("ext");
        JsonNode breakPoint = ext.path("break_point");
        assertTrue(breakPoint.isObject(), "paused 状态应有 break_point");
        assertEquals(2, breakPoint.path("index").asInt());
        assertEquals(0, breakPoint.path("state").asInt());
        assertEquals(0.6, breakPoint.path("progress").asDouble(), 0.001);
        assertEquals(0, breakPoint.path("wayline_id").asInt());
        assertEquals(1282, breakPoint.path("break_reason").asInt()); // 用户中断
        assertTrue(breakPoint.has("latitude"));
        assertTrue(breakPoint.has("longitude"));
        assertTrue(breakPoint.has("height"));
        assertTrue(breakPoint.has("attitude_head"));
    }

    @SuppressWarnings("unchecked")
    @DisplayName("TC-WAYLINE-007：flighttask_progress status 枚举")
    @Test
    void publishProgressWithOkStatus() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        // stepIndex=5 → Dock3 current_step=stepSequence()[5]=35（最后一步）
        simulator.publishProgress("ok", 5, 100);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals("ok", node.path("data").path("output").path("status").asText());
        assertEquals(35, node.path("data").path("output").path("progress").path("current_step").asInt()); // Dock3 stepSequence[5]=35
        assertEquals(100, node.path("data").path("output").path("progress").path("percent").asInt());
    }

    // ==================== TC-WAYLINE-012：current_step 版本化（Dock1 vs Dock2/3） ====================

    /**
     * current_step 按 Dock 型号版本化：同一 stepIndex 在不同型号对应不同 current_step 值。
     * Dock1 stepIndex=2 → 24（进入返航检查）；Dock3 stepIndex=2 → 26（进入返航检查）。
     * 两者 step 值不同但语义相同（Dock2/3 因多 2 个前置步骤导致偏移）。
     */
    @SuppressWarnings("unchecked")
    @DisplayName("TC-WAYLINE-012：current_step 步骤编号 Dock1 vs Dock2/3 不同")
    @Test
    void currentStepVersionDifferenceDock1VsDock3() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        // Dock1: stepIndex=2 → current_step=24（进入返航检查）
        MqttClientManager mqtt1 = Mockito.mock(MqttClientManager.class);
        WaylineTaskSimulator sim1 = new WaylineTaskSimulator(
                testProps(), mqtt1, new DeviceState(), objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK1), diagnosticRecorder(), new DockTopicSchema());
        sim1.publishProgress("in_progress", 2, 60);
        ArgumentCaptor<String> captor1 = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt1).publish(Mockito.anyString(), captor1.capture());
        JsonNode node1 = objectMapper.readTree(captor1.getValue());
        assertEquals(24, node1.path("data").path("output").path("progress").path("current_step").asInt()); // Dock1 stepSequence[2]=24

        // Dock3: stepIndex=2 → current_step=26（进入返航检查）
        MqttClientManager mqtt3 = Mockito.mock(MqttClientManager.class);
        WaylineTaskSimulator sim3 = new WaylineTaskSimulator(
                testProps(), mqtt3, new DeviceState(), objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());
        sim3.publishProgress("in_progress", 2, 60);
        ArgumentCaptor<String> captor3 = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt3).publish(Mockito.anyString(), captor3.capture());
        JsonNode node3 = objectMapper.readTree(captor3.getValue());
        assertEquals(26, node3.path("data").path("output").path("progress").path("current_step").asInt()); // Dock3 stepSequence[2]=26
    }

    // ==================== TC-WAYLINE-012b：break_reason 型号校验 ====================

    /**
     * break_reason 按型号校验：
     * <ul>
     *   <li>528=接近用户自定义飞行区边界：仅 Dock1（Dock2/Dock3 拒绝）</li>
     *   <li>529=有障碍物或者禁飞区域：仅 Dock2（Dock1/Dock3 拒绝）</li>
     *   <li>1565=航线避障紧急刹停：三版本共有（Dock1/Dock2/Dock3 均接受）</li>
     * </ul>
     * 核实依据：[Dock1/Dock2/Dock3 wayline.html] break_reason 枚举对比
     */
    @DisplayName("TC-WAYLINE-012b：break_reason 型号校验")
    @Test
    void breakReasonValidationByDockType() {
        ObjectMapper objectMapper = new ObjectMapper();
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        // --- 528=接近用户自定义飞行区边界（仅 Dock1）---
        // Dock1 接受 528
        MqttClientManager mqtt1 = Mockito.mock(MqttClientManager.class);
        WaylineTaskSimulator sim1 = new WaylineTaskSimulator(
                testProps(), mqtt1, new DeviceState(), objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK1), diagnosticRecorder(), new DockTopicSchema());
        assertTrue(sim1.publishProgressFailedWithBreakReason(528));
        Mockito.verify(mqtt1).publish(Mockito.anyString(), Mockito.any());

        // Dock2 拒绝 528
        MqttClientManager mqtt2 = Mockito.mock(MqttClientManager.class);
        WaylineTaskSimulator sim2 = new WaylineTaskSimulator(
                testProps(), mqtt2, new DeviceState(), objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK2), diagnosticRecorder(), new DockTopicSchema());
        assertFalse(sim2.publishProgressFailedWithBreakReason(528));
        Mockito.verify(mqtt2, Mockito.never()).publish(Mockito.anyString(), Mockito.any());

        // Dock3 拒绝 528
        MqttClientManager mqtt3 = Mockito.mock(MqttClientManager.class);
        WaylineTaskSimulator sim3 = new WaylineTaskSimulator(
                testProps(), mqtt3, new DeviceState(), objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());
        assertFalse(sim3.publishProgressFailedWithBreakReason(528));
        Mockito.verify(mqtt3, Mockito.never()).publish(Mockito.anyString(), Mockito.any());

        // --- 529=有障碍物或者禁飞区域（仅 Dock2）---
        // Dock1 拒绝 529
        assertFalse(sim1.publishProgressFailedWithBreakReason(529));

        // Dock2 接受 529
        assertTrue(sim2.publishProgressFailedWithBreakReason(529));
        Mockito.verify(mqtt2, Mockito.times(1)).publish(Mockito.anyString(), Mockito.any());

        // Dock3 拒绝 529
        assertFalse(sim3.publishProgressFailedWithBreakReason(529));

        // --- 1565=航线避障紧急刹停（三版本共有）---
        // Dock1 接受 1565
        assertTrue(sim1.publishProgressFailedWithBreakReason(1565));

        // Dock2 接受 1565
        assertTrue(sim2.publishProgressFailedWithBreakReason(1565));

        // Dock3 接受 1565（之前错误排除，已修正）
        assertTrue(sim3.publishProgressFailedWithBreakReason(1565));
        Mockito.verify(mqtt3).publish(Mockito.anyString(), Mockito.any());
    }

    // ==================== TC-WAYLINE-013：flighttask_stop 仅 Dock2/3 支持 ====================

    @DisplayName("TC-WAYLINE-013：flighttask_stop 仅 Dock2/3 支持")
    @Test
    void flighttaskStopRejectedOnDock1() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK1), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> result = invokeCommand(simulator, "flighttask_stop", null);

        // Dock1 不支持 flighttask_stop，返回 result=1
        assertEquals(1, result.get("result"));
    }

    @DisplayName("TC-WAYLINE-013：flighttask_stop 仅 Dock2/3 支持")
    @Test
    void flighttaskStopAcceptedOnDock3() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> result = invokeCommand(simulator, "flighttask_stop", null);

        // Dock3 支持 flighttask_stop，返回 result=0
        assertEquals(0, result.get("result"));
    }

    // ==================== TC-WAYLINE-014：return_specific_home 仅 Dock2/3 支持 ====================

    @DisplayName("TC-WAYLINE-014：return_specific_home 仅 Dock2/3 支持")
    @Test
    void returnSpecificHomeRejectedOnDock1() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK1), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> result = invokeCommand(simulator, "return_specific_home", null);

        // Dock1 不支持 return_specific_home，返回 result=1
        assertEquals(1, result.get("result"));
    }

    @DisplayName("TC-WAYLINE-014：return_specific_home 仅 Dock2/3 支持")
    @Test
    void returnSpecificHomeAcceptedOnDock2() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK2), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> result = invokeCommand(simulator, "return_specific_home", null);

        // Dock2 支持 return_specific_home，返回 result=0
        assertEquals(0, result.get("result"));
    }

    // ==================== TC-WAYLINE-015：flight_setup_abort 仅 Dock1 支持 ====================

    @DisplayName("TC-WAYLINE-015：flight_setup_abort 仅 Dock1 支持")
    @Test
    void flightSetupAbortAcceptedOnDock1() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK1), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> result = invokeCommand(simulator, "flight_setup_abort", null);

        // Dock1 支持 flight_setup_abort，返回 result=0
        assertEquals(0, result.get("result"));
    }

    @DisplayName("TC-WAYLINE-015：flight_setup_abort 仅 Dock1 支持")
    @Test
    void flightSetupAbortRejectedOnDock2() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK2), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> result = invokeCommand(simulator, "flight_setup_abort", null);

        // Dock2 不支持 flight_setup_abort，返回 result=1
        assertEquals(1, result.get("result"));
    }

    @DisplayName("TC-WAYLINE-015：flight_setup_abort 仅 Dock1 支持")
    @Test
    void flightSetupAbortRejectedOnDock3() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        Map<String, Object> result = invokeCommand(simulator, "flight_setup_abort", null);

        // Dock3 不支持 flight_setup_abort，返回 result=1
        assertEquals(1, result.get("result"));
    }

    // ==================== 通用命令不受 Dock 类型限制 ====================

    @DisplayName("TC-WAYLINE-021：flighttask_undo vs flighttask_stop 语义区分")
    @Test
    void commonCommandsAcceptedOnAllDocks() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        // flighttask_undo 在所有 Dock 上都支持
        for (DockModel dockType : new DockModel[]{DockModel.DOCK1, DockModel.DOCK2, DockModel.DOCK3}) {
            WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                    testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                    runtimeConfig(dockType), diagnosticRecorder(), new DockTopicSchema());

            Map<String, Object> result = invokeCommand(simulator, "flighttask_undo", null);
            assertEquals(0, result.get("result"),
                    "flighttask_undo 应在 " + dockType.shortName() + " 上返回 result=0");
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
    @DisplayName("TC-WAYLINE-022：飞行中取消任务返回 326109 拒绝")
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
                    runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

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
    @DisplayName("TC-WAYLINE-023：异常态取消任务返回 326108 拒绝")
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
                    runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

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
    @DisplayName("TC-LOC-004：return_home_info 使用机场位置")
    @Test
    void returnHomeInfoUsesRuntimeConfigLocation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        // 使用自定义位置（非 yml 默认值）验证取自 runtimeConfig
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockType()).thenReturn(DockModel.DOCK3);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK3-SN");
        Mockito.when(rc.getLocationLatitude()).thenReturn(31.23);
        Mockito.when(rc.getLocationLongitude()).thenReturn(121.47);
        Mockito.when(rc.getLocationHeight()).thenReturn(10.0);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                rc, diagnosticRecorder(), new DockTopicSchema());

        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "FLIGHT-HOME-001");

        Method m = WaylineTaskSimulator.class.getDeclaredMethod("publishReturnHomeInfo");
        m.setAccessible(true);
        m.invoke(simulator);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals("return_home_info", node.path("method").asText());
        // 返航轨迹：drone 当前位置 → (可选上升点) → 机场位置
        // 最后一个点为机场位置
        JsonNode pathPoints = node.path("data").path("planned_path_points");
        JsonNode lastPoint = pathPoints.get(pathPoints.size() - 1);
        assertEquals(31.23, lastPoint.path("latitude").asDouble());
        assertEquals(121.47, lastPoint.path("longitude").asDouble());
        assertEquals(10.0, lastPoint.path("height").asDouble());
        assertEquals("FLIGHT-HOME-001", node.path("data").path("flight_id").asText());

        // 蛙跳任务字段
        assertEquals("DOCK3-SN", node.path("data").path("home_dock_sn").asText());
        JsonNode multiDockHomeInfo = node.path("data").path("multi_dock_home_info");
        assertTrue(multiDockHomeInfo.isArray());
        assertEquals(1, multiDockHomeInfo.size());
        assertEquals("DOCK3-SN", multiDockHomeInfo.get(0).path("sn").asText());
        assertEquals(3, multiDockHomeInfo.get(0).path("plan_status").asInt());
        assertTrue(multiDockHomeInfo.get(0).has("estimated_battery_consumption"));
        assertTrue(multiDockHomeInfo.get(0).has("home_distance"));
    }

    /**
     * TC-WAYLINE-016：Dock1 的 return_home_info 不含蛙跳字段（home_dock_sn / multi_dock_home_info）。
     * 核实依据：[Dock1 wayline.html] return_home_info Data 仅 planned_path_points / last_point_type / flight_id
     */
    @SuppressWarnings("unchecked")
    @DisplayName("TC-WAYLINE-016：return_home_info 蛙跳字段仅 Dock2/3")
    @Test
    void returnHomeInfoDock1NoFrogLeapFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK1), diagnosticRecorder(), new DockTopicSchema());

        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "FLIGHT-001");

        Method m = WaylineTaskSimulator.class.getDeclaredMethod("publishReturnHomeInfo");
        m.setAccessible(true);
        m.invoke(simulator);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals("return_home_info", node.path("method").asText());
        // Dock1 仅有 planned_path_points / last_point_type / flight_id
        assertTrue(node.path("data").has("planned_path_points"));
        assertTrue(node.path("data").has("last_point_type"));
        assertTrue(node.path("data").has("flight_id"));
        // Dock1 不含蛙跳字段
        assertFalse(node.path("data").has("home_dock_sn"));
        assertFalse(node.path("data").has("multi_dock_home_info"));
    }

    /**
     * flighttask_prepare 提取 rth_altitude 到 state，供 return_home_info 使用。
     * DJI 文档约束：rth_altitude int, min=20, max=1500, 单位 m（相对起飞点 ALT）。
     */
    @DisplayName("TC-WAYLINE-001：flighttask_prepare 回复 + 机场状态更新")
    @Test
    void prepareExtractsRthAltitudeToState() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK1), diagnosticRecorder(), new DockTopicSchema());

        // 默认值为 0
        assertEquals(0, state.getRthAltitude());

        // 模拟 flighttask_prepare 请求
        JsonNode data = objectMapper.readTree("""
            {
                "flight_id": "FLIGHT-001",
                "task_type": 0,
                "rth_altitude": 120,
                "out_of_control_action": 0,
                "exit_wayline_when_rc_lost": 0,
                "file": {"url": "https://example.com/test.kmz", "fingerprint": "abc"}
            }
            """);

        Map<String, Object> result = invokeCommand(simulator, "flighttask_prepare", data);

        assertEquals(0, result.get("result"));
        assertEquals(120, state.getRthAltitude());
    }

    // ==================== TC-WAYLINE-019：flighttask_ready 事件结构 ====================

    /**
     * flighttask_ready 事件结构验证：
     * method=flighttask_ready, data.flight_ids 为传入的任务 ID 数组。
     */
    @SuppressWarnings("unchecked")
    @DisplayName("TC-WAYLINE-017：flighttask_ready 事件")
    @Test
    void flighttaskReadyEventStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK3-SN");

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                rc, diagnosticRecorder(), new DockTopicSchema());

        simulator.publishFlighttaskReady(List.of("TASK-A", "TASK-B"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals("flighttask_ready", node.path("method").asText());
        JsonNode flightIds = node.path("data").path("flight_ids");
        assertTrue(flightIds.isArray());
        assertEquals(2, flightIds.size());
        assertEquals("TASK-A", flightIds.get(0).asText());
        assertEquals("TASK-B", flightIds.get(1).asText());
    }

    // ==================== TC-WAYLINE-020：device_exit_homing_notify 事件结构 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-WAYLINE-018：device_exit_homing_notify 事件")
    @Test
    void deviceExitHomingNotifyEventStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        simulator.publishDeviceExitHomingNotify(1, 3);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals("device_exit_homing_notify", node.path("method").asText());
        assertEquals(1, node.path("need_reply").asInt()); // need_reply=1
        assertEquals("DOCK3-SN", node.path("data").path("sn").asText());
        assertEquals(1, node.path("data").path("action").asInt());
        assertEquals(3, node.path("data").path("reason").asInt());
    }

    // ==================== TC-WAYLINE-020：flight_setup_exception_notify 事件结构（Dock1） ====================

    /**
     * flight_setup_exception_notify 事件结构验证（Dock1 机场任务准备异常通知）：
     * method=flight_setup_exception_notify, need_reply=1,
     * data.flight_id / data.flight_type / data.sn / data.timeout_time(int) / data.timestamp(double)。
     * flight_id 按 Example 包含（M-2 待真机验证）。
     */
    @SuppressWarnings("unchecked")
    @DisplayName("TC-WAYLINE-020：flight_setup_exception_notify 事件（Dock1 专有）")
    @Test
    void flightSetupExceptionNotifyEventStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockType()).thenReturn(DockModel.DOCK1);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK1-SN");

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                rc, diagnosticRecorder(), new DockTopicSchema());

        boolean sent = simulator.publishFlightSetupExceptionNotify("FLIGHT-EXC-001", 6, 1);
        assertTrue(sent); // Dock1 支持已发送

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals("flight_setup_exception_notify", node.path("method").asText());
        assertEquals(1, node.path("need_reply").asInt()); // need_reply=1
        assertEquals("FLIGHT-EXC-001", node.path("data").path("flight_id").asText()); // flight_id 按 Example 包含
        assertEquals(1, node.path("data").path("flight_type").asInt());
        assertEquals("DOCK1-SN", node.path("data").path("sn").asText());
        assertEquals(6, node.path("data").path("timeout_time").asInt());
        assertTrue(node.path("data").path("timestamp").isDouble()); // timestamp 为 double 类型
    }

    // ==================== TC-WAYLINE-020b：flight_setup_exception_notify Dock2/3 拒绝（P-8） ====================

    /**
     * flight_setup_exception_notify 仅 Dock1 支持，Dock2/3 调用应拒绝上报（P-8 型号能力不匹配）。
     */
    @DisplayName("TC-WAYLINE-020：flight_setup_exception_notify 事件（Dock1 专有）")
    @Test
    void flightSetupExceptionNotifyDock2Rejected() {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK2), diagnosticRecorder(), new DockTopicSchema());

        boolean sent = simulator.publishFlightSetupExceptionNotify("FLIGHT-EXC-001", 6, 1);

        assertFalse(sent); // Dock2 不支持，未发送
        Mockito.verify(mqtt, Mockito.never()).publish(Mockito.anyString(), Mockito.any());
    }

    // ==================== TC-WAYLINE-021：in_flight_wayline_progress 事件结构 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-WAYLINE-019：in_flight_wayline_progress 事件（Dock2/3）")
    @Test
    void inFlightWaylineProgressEventStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        simulator.publishInFlightWaylineProgress("WAYLINE-001", 50, 3, 0, 2);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals("in_flight_wayline_progress", node.path("method").asText());
        assertEquals(1, node.path("need_reply").asInt()); // SDK EventMethod.IN_FLIGHT_WAYLINE_PROGRESS.needReply()=1
        assertEquals("WAYLINE-001", node.path("data").path("in_flight_wayline_id").asText());
        assertEquals(50, node.path("data").path("progress").path("percent").asInt());
        assertEquals(3, node.path("data").path("status").asInt());
        assertEquals(0, node.path("data").path("result").asInt());
        assertEquals(2, node.path("data").path("way_point_index").asInt());
    }

    // ==================== TC-WAYLINE-024：flighttask_execute 解析蛙跳任务参数 ====================

    /**
     * 验证 flighttask_execute 收到 multi_dock_task 蛙跳参数时能正确解析并返回 result=0。
     * 当前仅解析记录，不用于执行逻辑。
     */
    @DisplayName("补充测试：flighttask_execute 解析蛙跳任务参数")
    @Test
    void flighttaskExecuteParsesMultiDockTask() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        // 构造包含 multi_dock_task 的 flighttask_execute 请求
        String json = "{\"flight_id\": \"TEST-FLIGHT-001\", \"multi_dock_task\": {"
                + "\"wireless_link_topo\": {"
                + "  \"secret_code\": [0,0,0,0,1,0,0,0,123,114,19,203,192,100,244,160,146,228,196,213,105,220,176,147,87,182,90,210],"
                + "  \"center_node\": {\"sdr_id\": 933765657, \"sn\": \"1581F6Q8D245P00EKS87\"},"
                + "  \"leaf_nodes\": [{\"sdr_id\": 920128532, \"sn\": \"7CTDM5900B3X1B\", \"control_source_index\": 1},"
                + "                   {\"sdr_id\": 911741468, \"sn\": \"7CTDM5900BK07M\", \"control_source_index\": 2}]"
                + "},"
                + "\"dock_infos\": ["
                + "  {\"sn\": \"7CTDM5900B3X1B\", \"dock_type\": \"takeoff\", \"index\": 1, \"latitude\": 37.348, \"longitude\": 116.528, \"height\": 30.811},"
                + "  {\"sn\": \"7CTDM5900BK07M\", \"dock_type\": \"landing\", \"index\": 2, \"latitude\": 37.336, \"longitude\": 116.554, \"height\": 32.314}"
                + "]}}";
        JsonNode data = objectMapper.readTree(json);

        // 通过反射调用 handleWaylineCommand
        Method m = WaylineTaskSimulator.class.getDeclaredMethod("handleWaylineCommand", String.class, JsonNode.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) m.invoke(simulator, "flighttask_execute", data);

        // 验证返回 result=0
        assertEquals(0, result.get("result"));
    }

    // ==================== TC-LOC-005：无人机位置随飞行步骤更新 ====================

    /**
     * 各执行步骤索引的无人机位置更新策略（TC-WAYLINE-024 连续插值语义，三版本通用）：
     * stepIndex 调用后 mode_code 立即切换，位置设定为插值目标点（不瞬移），
     * advanceInterpolation 迭代逐步推进。
     * stepIndex 1（起飞）→ 目标=机场位置, height=0, mode_code=4
     * stepIndex 2（返航检查）→ 目标=机场+偏移, height=50, mode_code=5
     * stepIndex 3（降落）→ 目标=机场位置, height=20, mode_code=9
     * stepIndex 4（退出工作模式）→ 目标=机场位置, height=0, mode_code=10
     */
    @DisplayName("TC-LOC-005：无人机位置随飞行步骤更新（插值目标点语义）")
    @Test
    void dronePositionUpdatesByFlightStep() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        Method m = WaylineTaskSimulator.class.getDeclaredMethod("updateDroneStateByStepIndex", int.class);
        m.setAccessible(true);
        // 插值迭代前置条件：currentFlightId 非空
        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "FLIGHT-LOC-005");

        // stepIndex 1（起飞）：mode_code 立即切换，目标=机场位置、height=0
        m.invoke(simulator, 1);
        assertEquals(4, state.getDroneModeCode());
        assertInterpTarget(simulator, 30.67, 104.07, 0.0);

        // stepIndex 2（返航检查）：mode_code=5，目标=机场偏移、height=50
        m.invoke(simulator, 2);
        assertEquals(5, state.getDroneModeCode());
        assertInterpTarget(simulator, 30.67 + 0.001, 104.07 + 0.001, 50.0);

        // stepIndex 3（降落）：mode_code=9，目标=机场位置、height=20
        m.invoke(simulator, 3);
        assertEquals(9, state.getDroneModeCode());
        assertInterpTarget(simulator, 30.67, 104.07, 20.0);

        // stepIndex 4（退出工作模式）：mode_code=10，目标=机场位置、height=0
        m.invoke(simulator, 4);
        assertEquals(10, state.getDroneModeCode());
        assertInterpTarget(simulator, 30.67, 104.07, 0.0);
    }

    /** 反射断言插值目标点字段（lat/lng/height） */
    private void assertInterpTarget(WaylineTaskSimulator simulator, double lat, double lng, double height)
            throws Exception {
        Field latF = WaylineTaskSimulator.class.getDeclaredField("targetLatitude");
        Field lngF = WaylineTaskSimulator.class.getDeclaredField("targetLongitude");
        Field hF = WaylineTaskSimulator.class.getDeclaredField("targetHeight");
        latF.setAccessible(true);
        lngF.setAccessible(true);
        hF.setAccessible(true);
        assertEquals(lat, latF.getDouble(simulator), 1e-9, "插值目标纬度");
        assertEquals(lng, lngF.getDouble(simulator), 1e-9, "插值目标经度");
        assertEquals(height, hF.getDouble(simulator), 1e-9, "插值目标高度");
    }

    // ==================== TC-LOC-006：任务完成后无人机位置重置 ====================

    /**
     * 任务完成（completeTask）后无人机位置重置为机场位置，避免前端显示残留飞行偏移。
     */
    @DisplayName("TC-LOC-006：任务完成后无人机位置重置")
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
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

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

    // ==================== TC-LOC-016：return_home 后无人机位置更新到机场 ====================

    /**
     * return_home 指令立即设置 mode_code=9（自动返航），并调度延迟任务更新位置。
     */
    @SuppressWarnings("unchecked")
    @DisplayName("TC-LOC-016：return_home 后无人机位置更新到机场")
    @Test
    void returnHomeSetsReturnModeImmediately() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        // 模拟飞行中状态
        state.setDroneLatitude(31.0);
        state.setDroneLongitude(122.0);
        state.setDroneHeight(80.0);
        state.setDroneModeCode(5);

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);
        DiagnosticLogRecorder recorder = diagnosticRecorder();

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), recorder, new DockTopicSchema());

        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "FLIGHT-RTH-001");

        Map<String, Object> result = invokeCommand(simulator, "return_home", null);

        // services_reply result=0 + output.status（Dock3 文档定义）
        assertEquals(0, result.get("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        assertEquals("ok", output.get("status"));
        // 立即进入返航模式
        assertEquals(9, state.getDroneModeCode());
        // 位置尚未更新（仍在飞行中位置）
        assertEquals(31.0, state.getDroneLatitude());
        assertEquals(122.0, state.getDroneLongitude());
        // M-2：return_home 后续行为（不发 return_home_info、无进度上报）未确认，记录诊断日志
        Mockito.verify(recorder).record(Mockito.eq(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE),
                Mockito.eq("return_home"), Mockito.contains("return_home"));
    }

    /**
     * 返航完成后（completeReturnHome）无人机位置更新到机场，mode_code=0, droneInDock=true。
     */
    @DisplayName("TC-LOC-016：return_home 后无人机位置更新到机场")
    @Test
    void returnHomeCompletesWithDroneAtAirport() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        // 模拟飞行中位置（偏离机场）
        state.setDroneLatitude(31.0);
        state.setDroneLongitude(122.0);
        state.setDroneHeight(80.0);
        state.setDroneModeCode(9); // 返航中

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "FLIGHT-RTH-002");

        Method m = WaylineTaskSimulator.class.getDeclaredMethod("completeReturnHome");
        m.setAccessible(true);
        m.invoke(simulator);

        // 无人机位置更新到机场
        assertEquals(30.67, state.getDroneLatitude());
        assertEquals(104.07, state.getDroneLongitude());
        assertEquals(0.0, state.getDroneHeight());
        assertEquals(0, state.getDroneModeCode());
        assertTrue(state.isDroneInDock());
    }

    /**
     * return_home_cancel 取消返航延迟任务，无人机位置不变。
     */
    @DisplayName("TC-WAYLINE-005：return_home/return_home_cancel/return_specific_home 回复")
    @Test
    void returnHomeCancelStopsPositionUpdate() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setDroneLatitude(31.0);
        state.setDroneLongitude(122.0);
        state.setDroneHeight(80.0);
        state.setDroneModeCode(9);

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, commandHandler, mediaUpload,
                runtimeConfig(DockModel.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        // 先触发 return_home 调度延迟任务
        invokeCommand(simulator, "return_home", null);
        // 再取消返航
        Map<String, Object> result = invokeCommand(simulator, "return_home_cancel", null);

        assertEquals(0, result.get("result"));
        // 位置不应改变（仍在当前位置）
        assertEquals(31.0, state.getDroneLatitude());
        assertEquals(122.0, state.getDroneLongitude());
        assertEquals(80.0, state.getDroneHeight());
    }
}
