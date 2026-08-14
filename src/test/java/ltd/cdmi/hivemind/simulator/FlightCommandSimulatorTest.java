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
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FlightCommandSimulator 单元测试。
 * <p>覆盖：
 * <ul>
 *   <li>TC-FLY-029：Dock2/Dock3 特有字段解析（rth_mode/commander_flight_mode/flight_safety_advance_check）</li>
 *   <li>TC-FLY-030：MQTT 消息体不泄漏内部字段</li>
 * </ul>
 */
class FlightCommandSimulatorTest {

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null
        );
    }

    private RuntimeConfig runtimeConfig(DeviceType dockType) {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockType()).thenReturn(dockType);
        Mockito.when(rc.getDockSn()).thenReturn("dock-sn-test");
        Mockito.when(rc.getLocationLatitude()).thenReturn(30.67);
        Mockito.when(rc.getLocationLongitude()).thenReturn(104.07);
        Mockito.when(rc.getLocationHeight()).thenReturn(500.0);
        return rc;
    }

    private DiagnosticLogRecorder diagnosticRecorder() {
        return Mockito.mock(DiagnosticLogRecorder.class);
    }

    // ==================== TC-FLY-029：Dock2/Dock3 特有字段解析 ====================

    @Test
    void dock3SpecificFieldsParsed() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        // Dock3 下发含 rth_mode/commander_flight_mode/flight_safety_advance_check 的指令
        String json = """
                {
                  "flight_id": "FLIGHT-001",
                  "target_latitude": 12.23,
                  "target_longitude": 12.32,
                  "target_height": 100,
                  "security_takeoff_height": 80,
                  "rth_altitude": 100,
                  "rth_mode": 1,
                  "rc_lost_action": 2,
                  "commander_mode_lost_action": 1,
                  "commander_flight_mode": 1,
                  "commander_flight_height": 80,
                  "max_speed": 12,
                  "simulate_mission": {"is_enable": 0, "latitude": 0, "longitude": 0},
                  "flight_safety_advance_check": 1
                }
                """;
        JsonNode data = objectMapper.readTree(json);

        Map<String, Object> reply = simulator.handleTakeoffToPoint(data, "bid-test");

        // 验证 DeviceState 中存储了 Dock3 特有字段
        assertEquals(1, state.getRthMode(), "rth_mode 应为 1（设定高度）");
        assertEquals(1, state.getCommanderFlightMode(), "commander_flight_mode 应为 1（设定高度飞行）");
        assertEquals(1, state.getFlightSafetyAdvanceCheck(), "flight_safety_advance_check 应为 1（开启）");

        // 验证 services_reply 只含 result
        assertNotNull(reply);
        assertTrue(reply.containsKey("result"));
        assertEquals(0, reply.get("result"));
    }

    @Test
    void dock1MissingDock3FieldsDefaultsToZero() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK1), diagnosticRecorder(), new DockTopicSchema());

        // Dock1 不下发 rth_mode/commander_flight_mode/flight_safety_advance_check
        String json = """
                {
                  "flight_id": "FLIGHT-002",
                  "target_latitude": 12.23,
                  "target_longitude": 12.32,
                  "target_height": 100,
                  "security_takeoff_height": 80,
                  "rth_altitude": 100,
                  "rc_lost_action": 0,
                  "commander_mode_lost_action": 1,
                  "commander_flight_height": 80,
                  "max_speed": 12,
                  "simulate_mission": {"is_enable": 0, "latitude": 0, "longitude": 0}
                }
                """;
        JsonNode data = objectMapper.readTree(json);

        simulator.handleTakeoffToPoint(data, "bid-test");

        // Dock1 不下发时，asInt() 返回默认值 0
        assertEquals(0, state.getRthMode(), "Dock1 不下发 rth_mode，默认应为 0");
        assertEquals(0, state.getCommanderFlightMode(), "Dock1 不下发 commander_flight_mode，默认应为 0");
        assertEquals(0, state.getFlightSafetyAdvanceCheck(), "Dock1 不下发 flight_safety_advance_check，默认应为 0");
    }

    // ==================== TC-FLY-030：MQTT 消息体不泄漏内部字段 ====================

    @Test
    void servicesReplyDoesNotLeakInternalFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        String json = """
                {
                  "flight_id": "FLIGHT-003",
                  "target_latitude": 12.23,
                  "target_longitude": 12.32,
                  "target_height": 100,
                  "security_takeoff_height": 80,
                  "rth_altitude": 100,
                  "rth_mode": 1,
                  "rc_lost_action": 2,
                  "commander_mode_lost_action": 1,
                  "commander_flight_mode": 1,
                  "commander_flight_height": 80,
                  "max_speed": 12,
                  "simulate_mission": {"is_enable": 0, "latitude": 0, "longitude": 0},
                  "flight_safety_advance_check": 1
                }
                """;
        JsonNode data = objectMapper.readTree(json);

        Map<String, Object> reply = simulator.handleTakeoffToPoint(data, "bid-test");

        // services_reply 只含 result，不含 rth_mode/commander_flight_mode/flight_safety_advance_check
        assertNotNull(reply);
        assertEquals(1, reply.size(), "services_reply data 应只有 1 个字段");
        assertTrue(reply.containsKey("result"), "services_reply data 应只含 result");
        assertFalse(reply.containsKey("rth_mode"), "services_reply 不应包含 rth_mode");
        assertFalse(reply.containsKey("commander_flight_mode"), "services_reply 不应包含 commander_flight_mode");
        assertFalse(reply.containsKey("flight_safety_advance_check"), "services_reply 不应包含 flight_safety_advance_check");
    }

    // ==================== TC-FLY-028：rc_lost_action 遥控器失联模拟 ====================

    /**
     * rc_lost_action=0（悬停）：mode_code=0，位置不变。
     */
    @Test
    void rcLostHoverSetsModeCodeZero() {
        DeviceState state = new DeviceState();
        state.setDroneActivated(true);
        state.setDroneInDock(false);
        state.setDroneLatitude(31.0);
        state.setDroneLongitude(122.0);
        state.setDroneHeight(80.0);
        state.setDroneModeCode(5);
        state.setRcLostAction(0); // 悬停

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        DiagnosticLogRecorder recorder = diagnosticRecorder();

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK3), recorder, new DockTopicSchema());

        String err = simulator.triggerRcLost();

        assertNull(err, "triggerRcLost 应成功");
        assertEquals(0, state.getDroneModeCode(), "悬停 mode_code=0");
        assertEquals(31.0, state.getDroneLatitude(), "位置不变");
        assertEquals(80.0, state.getDroneHeight(), "高度不变");
        // 验证发布了 joystick_invalid_notify 事件
        Mockito.verify(mqtt).publishJson(
                Mockito.contains("/events"),
                Mockito.argThat(arg -> arg instanceof Map &&
                        "joystick_invalid_notify".equals(((Map<?, ?>) arg).get("method"))));
        // 悬停无推断行为，不记录 M-2
        Mockito.verify(recorder, Mockito.never()).record(Mockito.any(), Mockito.any(), Mockito.any());
    }

    /**
     * rc_lost_action=1（降落）：mode_code=12，延迟后原地降落（height=0, mode_code=0, droneInDock=false）。
     */
    @Test
    void rcLostLandingCompletesInPlace() throws Exception {
        DeviceState state = new DeviceState();
        state.setDroneActivated(true);
        state.setDroneInDock(false);
        state.setDroneLatitude(31.0);
        state.setDroneLongitude(122.0);
        state.setDroneHeight(80.0);
        state.setDroneModeCode(5);
        state.setRcLostAction(1); // 降落

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        DiagnosticLogRecorder recorder = diagnosticRecorder();

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK3), recorder, new DockTopicSchema());

        simulator.triggerRcLost();

        // 立即进入降落模式
        assertEquals(12, state.getDroneModeCode(), "降落中 mode_code=12");
        // 验证记录了 M-2 诊断日志（降落位置未得到 DJI 文档确认）
        Mockito.verify(recorder).record(
                Mockito.argThat(code -> "M-2".equals(code.code())),
                Mockito.eq("trigger_rc_lost"),
                Mockito.contains("rc_lost_action=1(降落)"));

        // 调用私有方法模拟降落完成
        java.lang.reflect.Method m = FlightCommandSimulator.class.getDeclaredMethod("completeRcLostLanding");
        m.setAccessible(true);
        m.invoke(simulator);

        // 原地降落：位置不变，高度=0，不在舱内
        assertEquals(0, state.getDroneModeCode(), "降落完成 mode_code=0");
        assertEquals(0.0, state.getDroneHeight(), "高度=0");
        assertFalse(state.isDroneInDock(), "原地降落 droneInDock=false");
        assertEquals(31.0, state.getDroneLatitude(), "经纬度不变");
        assertEquals(122.0, state.getDroneLongitude(), "经纬度不变");
    }

    /**
     * rc_lost_action=2（返航）：mode_code=9，延迟后归舱（位置=机场, mode_code=0, droneInDock=true）。
     */
    @Test
    void rcLostReturnHomeCompletesAtAirport() throws Exception {
        DeviceState state = new DeviceState();
        state.setDroneActivated(true);
        state.setDroneInDock(false);
        state.setDroneLatitude(31.0);
        state.setDroneLongitude(122.0);
        state.setDroneHeight(80.0);
        state.setDroneModeCode(5);
        state.setRcLostAction(2); // 返航

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        DiagnosticLogRecorder recorder = diagnosticRecorder();

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK3), recorder, new DockTopicSchema());

        simulator.triggerRcLost();

        // 立即进入返航模式
        assertEquals(9, state.getDroneModeCode(), "返航中 mode_code=9");
        // 验证记录了 M-2 诊断日志（不发return_home_info + 延迟归舱未得到 DJI 文档确认）
        Mockito.verify(recorder).record(
                Mockito.argThat(code -> "M-2".equals(code.code())),
                Mockito.eq("trigger_rc_lost"),
                Mockito.contains("rc_lost_action=2(返航)"));

        // 调用私有方法模拟返航完成
        java.lang.reflect.Method m = FlightCommandSimulator.class.getDeclaredMethod("completeRcLostReturnHome");
        m.setAccessible(true);
        m.invoke(simulator);

        // 归舱：位置=机场，mode_code=0，droneInDock=true
        assertEquals(0, state.getDroneModeCode(), "返航完成 mode_code=0");
        assertEquals(30.67, state.getDroneLatitude(), "位置=机场纬度");
        assertEquals(104.07, state.getDroneLongitude(), "位置=机场经度");
        assertEquals(0.0, state.getDroneHeight(), "高度=0");
        assertTrue(state.isDroneInDock(), "归舱 droneInDock=true");
    }

    /**
     * MQTT 未连接时拒绝触发失联。
     */
    @Test
    void rcLostRejectedWhenMqttDisconnected() {
        DeviceState state = new DeviceState();
        state.setRcLostAction(2);

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(false);

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        String err = simulator.triggerRcLost();

        assertNotNull(err, "MQTT 未连接应返回拒绝原因");
        assertTrue(err.contains("MQTT"), "拒绝原因应包含 MQTT");
    }

    // ==================== TC-FLY-031：rth_mode=0 拒绝执行 ====================

    /**
     * Dock2/Dock3 显式下发 rth_mode=0（智能高度）→ 拒绝执行，返回 result 非 0。
     */
    @Test
    void rthModeZeroRejected() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        DiagnosticLogRecorder recorder = Mockito.mock(DiagnosticLogRecorder.class);

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK3), recorder, new DockTopicSchema());

        String json = """
                {
                  "flight_id": "FLIGHT-RTH0",
                  "target_latitude": 12.23,
                  "target_longitude": 12.32,
                  "target_height": 100,
                  "security_takeoff_height": 80,
                  "rth_altitude": 100,
                  "rth_mode": 0,
                  "rc_lost_action": 2,
                  "commander_mode_lost_action": 1,
                  "commander_flight_mode": 1,
                  "commander_flight_height": 80,
                  "max_speed": 12,
                  "simulate_mission": {"is_enable": 0, "latitude": 0, "longitude": 0}
                }
                """;
        JsonNode data = objectMapper.readTree(json);

        Map<String, Object> reply = simulator.handleTakeoffToPoint(data, "bid-rth0");

        // 拒绝执行：result 非 0
        assertNotNull(reply);
        assertEquals(1, reply.get("result"), "rth_mode=0 应返回 result=1（拒绝）");
        // 不更新 DeviceState（flight_id 不应被设置）
        assertNull(state.getCurrentFlightId(), "拒绝后不应更新 DeviceState");
        // 记录 M-2 诊断日志（模拟器未确认真机反应，待真机验证）
        Mockito.verify(recorder).record(
                Mockito.argThat(code -> "M-2".equals(code.code())),
                Mockito.eq("takeoff_to_point"),
                Mockito.contains("rth_mode=0"));
        // 不记录 P-10（rth_mode=0 是合法协议值，不是平台错误）
        Mockito.verify(recorder, Mockito.never()).record(
                Mockito.argThat(code -> "P-10".equals(code.code())),
                Mockito.any(),
                Mockito.any());
    }

    /**
     * Dock1 不下发 rth_mode 字段（isMissingNode）→ 不拒绝，正常执行。
     */
    @Test
    void dock1MissingRthModeNotRejected() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        DiagnosticLogRecorder recorder = Mockito.mock(DiagnosticLogRecorder.class);

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK1), recorder, new DockTopicSchema());

        // Dock1 不下发 rth_mode 字段
        String json = """
                {
                  "flight_id": "FLIGHT-DOCK1",
                  "target_latitude": 12.23,
                  "target_longitude": 12.32,
                  "target_height": 100,
                  "security_takeoff_height": 80,
                  "rth_altitude": 100,
                  "rc_lost_action": 0,
                  "commander_mode_lost_action": 1,
                  "commander_flight_height": 80,
                  "max_speed": 12,
                  "simulate_mission": {"is_enable": 0, "latitude": 0, "longitude": 0}
                }
                """;
        JsonNode data = objectMapper.readTree(json);

        Map<String, Object> reply = simulator.handleTakeoffToPoint(data, "bid-dock1");

        // 不拒绝：result=0
        assertNotNull(reply);
        assertEquals(0, reply.get("result"), "Dock1 缺 rth_mode 不应拒绝");
        // 正常更新 DeviceState
        assertEquals("FLIGHT-DOCK1", state.getCurrentFlightId(), "应正常设置 flight_id");
        // 不记录 P-10 诊断日志
        Mockito.verify(recorder, Mockito.never()).record(Mockito.any(), Mockito.any(), Mockito.any());
    }

    // ==================== TC-FLY-019: fly_to_point_stop 取消延迟任务 ====================

    @Test
    @DisplayName("TC-FLY-019: fly_to_point_stop 取消延迟任务，wayline_ok 不再发布且位置不更新")
    void flyToPointStopCancelsScheduledTasks() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        FlightCommandSimulator simulator = new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(DeviceType.DOCK3), diagnosticRecorder(), new DockTopicSchema());

        state.setDroneActivated(true);
        state.setDroneInDock(false);
        state.setDroneLatitude(22.0);
        state.setDroneLongitude(113.0);
        state.setDroneElevation(50.0);
        state.setSimulateMissionEnable(0);

        double targetLat = 22.1;
        double targetLng = 113.1;
        double targetHeight = 100.0;
        String flyToJson = """
                {
                  "fly_to_id": "FLY-001",
                  "points": [{"latitude": %s, "longitude": %s, "height": %s}],
                  "max_speed": 10
                }
                """.formatted(targetLat, targetLng, targetHeight);
        JsonNode flyToData = objectMapper.readTree(flyToJson);

        simulator.handleFlyToPoint(flyToData, "bid-001");
        // 立即调用 stop（未到 2s / 4s 延迟）
        Map<String, Object> reply = simulator.handleFlyToPointStop("bid-001");

        // services_reply
        assertNotNull(reply);
        assertEquals(0, reply.get("result"), "fly_to_point_stop 应返回 result=0");

        // 等待超过 4s（PROGRESS_INTERVAL_SECONDS * 2），确认延迟任务已被取消
        Thread.sleep(2500);

        // 验证无人机位置未更新到目标点（停留在起点）
        assertEquals(22.0, state.getDroneLatitude(), "fly_to_point_stop 后纬度不应更新到目标点");
        assertEquals(113.0, state.getDroneLongitude(), "fly_to_point_stop 后经度不应更新到目标点");
        assertEquals(50.0, state.getDroneElevation(), "fly_to_point_stop 后高度不应更新到目标点");
    }
}
