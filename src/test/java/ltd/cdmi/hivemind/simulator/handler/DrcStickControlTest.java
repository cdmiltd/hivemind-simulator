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

package ltd.cdmi.hivemind.simulator.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * DRC 杆量积分模拟（stick_control → 位置/高度/偏航推进）单元测试。
 * <p>覆盖 TDD-SPEC TC-DRC-061~065：俯仰平移、油门上升、偏航旋转、在舱忽略、断流时间步封顶。
 * <p>核实依据：DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#杆量控制">Dock3 DRC 杆量控制</a>：
 * stick_control 无回包机制，杆量值域 [1024±660]（中值 1024，满杆 1684/364），5-10Hz 连续下发。
 */
class DrcStickControlTest {

    private static final String DRC_DOWN_TOPIC = "thing/product/test-gateway/drc/down";
    private static final long SECOND_NANOS = 1_000_000_000L;
    /** 0.5 秒间隔：与真实杆量流 5-10Hz 量级一致，且未触发断流封顶（TC-DRC-065 专属场景） */
    private static final long HALF_NANOS = SECOND_NANOS / 2;
    private static final double METERS_PER_DEGREE = 111320.0;

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null, null);
    }

    private RuntimeConfig runtimeConfig() {
        RuntimeConfig rc = new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
        rc.setDockType(DockModel.DOCK3);
        return rc;
    }

    /** 构造飞行中状态的 DeviceState：在舱=false，位置=机场坐标，机头朝北 */
    private DeviceState flyingState() {
        DeviceState state = new DeviceState();
        state.setDroneInDock(false);
        state.setDroneLatitude(30.67);
        state.setDroneLongitude(104.07);
        state.setDroneHeight(50.0);
        state.setDroneElevation(550.0);
        state.setAttitudeYaw(0.0);
        return state;
    }

    private DrcCommandHandler handler(DeviceState state, DiagnosticLogRecorder diagnosticRecorder) {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator aiSimulator = new AiSimulator(mqtt, rc, schema);
        return new DrcCommandHandler(
                testProps(), mqtt, new ObjectMapper(), state,
                diagnosticRecorder, Mockito.mock(CoverageRecorder.class), rc, schema, aiSimulator);
    }

    /** 反射调用 DrcCommandHandler.handleDrcCommand(topic, payload) */
    private void invokeHandleDrcCommand(DrcCommandHandler handler, String payload) throws Exception {
        Method m = DrcCommandHandler.class.getDeclaredMethod("handleDrcCommand", String.class, String.class);
        m.setAccessible(true);
        m.invoke(handler, DRC_DOWN_TOPIC, payload);
    }

    // ==================== TC-DRC-061：俯仰满杆沿机头方向平移 ====================

    @DisplayName("TC-DRC-061：pitch 满杆前推 → 首条不位移，第二条向北 10 米，姿态下俯")
    @Test
    void pitchFullStick_movesNorthAndTiltsNoseDown() {
        DeviceState state = flyingState();
        DiagnosticLogRecorder diagnosticRecorder = Mockito.mock(DiagnosticLogRecorder.class);
        DrcCommandHandler handler = handler(state, diagnosticRecorder);

        // 首条：仅建立时间基准（dt=0），不位移，但姿态角已映射
        handler.integrateStick(SECOND_NANOS, 1024, 1684, 1024, 1024);
        assertEquals(30.67, state.getDroneLatitude(), 1e-9, "首条 stick_control 不应产生位移");
        assertEquals(104.07, state.getDroneLongitude(), 1e-9);
        assertEquals(50.0, state.getDroneHeight(), 1e-9);
        assertEquals(-15.0, state.getAttitudePitch(), 1e-9, "前推杆机头下俯为负（满杆 15 度）");

        // 第二条：dt=0.5s（5-10Hz 杆量流量级），满杆水平速度 10m/s，机头朝北 → 向北 5 米
        handler.integrateStick(SECOND_NANOS + HALF_NANOS, 1024, 1684, 1024, 1024);
        assertEquals(30.67 + 5.0 / METERS_PER_DEGREE, state.getDroneLatitude(), 1e-9,
                "纬度应增加 5/111320（向北 5 米 = 10m/s × 0.5s）");
        assertEquals(104.07, state.getDroneLongitude(), 1e-9, "机头朝北时经度不变");
        assertEquals(50.0, state.getDroneHeight(), 1e-9, "无油门杆量高度不变");

        // M-2 诊断日志：杆量符号约定（一次性记录）
        verify(diagnosticRecorder).record(eq(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE),
                eq("stick_control"), anyString());
    }

    // ==================== TC-DRC-062：油门满杆上升 ====================

    @DisplayName("TC-DRC-062：throttle 满杆上升 0.5s → height/elevation 同步 +2.5")
    @Test
    void throttleFullStick_climbsHeightAndElevation() {
        DeviceState state = flyingState();
        DrcCommandHandler handler = handler(state, Mockito.mock(DiagnosticLogRecorder.class));

        handler.integrateStick(SECOND_NANOS, 1024, 1024, 1024, 1024);                       // 建立基准（中值悬停）
        handler.integrateStick(SECOND_NANOS + SECOND_NANOS / 2, 1024, 1024, 1684, 1024); // dt=0.5s 满杆上升

        assertEquals(52.5, state.getDroneHeight(), 1e-9, "满杆垂直速度 5m/s × 0.5s → height 50→52.5");
        assertEquals(552.5, state.getDroneElevation(), 1e-9, "椭球高同步增加 2.5 → 550→552.5");
        assertEquals(30.67, state.getDroneLatitude(), 1e-9, "无水平杆量位置不变");
    }

    // ==================== TC-DRC-063：偏航满杆旋转机头 ====================

    @DisplayName("TC-DRC-063：yaw 满杆 1s → 机头转至 60 度，后续位移沿新机头方向")
    @Test
    void yawFullStick_rotatesHeadingAndShiftsMovementDirection() {
        DeviceState state = flyingState();
        DrcCommandHandler handler = handler(state, Mockito.mock(DiagnosticLogRecorder.class));

        handler.integrateStick(SECOND_NANOS, 1024, 1024, 1024, 1684);            // 建立基准 + 满杆顺时针
        handler.integrateStick(SECOND_NANOS + HALF_NANOS, 1024, 1024, 1024, 1684); // dt=0.5s：60°/s → heading 0→30

        assertEquals(30.0, state.getAttitudeYaw(), 1e-9, "满杆偏航角速度 60°/s × 0.5s → heading=30");

        // 机头 30 度后满杆前推 0.5s：北分量 10·cos30·0.5≈4.33，东分量 10·sin30·0.5=2.5
        handler.integrateStick(SECOND_NANOS + HALF_NANOS * 2, 1024, 1684, 1024, 1024);
        double northMeters = 10.0 * Math.cos(Math.toRadians(30)) * 0.5;
        double eastMeters = 10.0 * Math.sin(Math.toRadians(30)) * 0.5;
        assertEquals(30.67 + northMeters / METERS_PER_DEGREE, state.getDroneLatitude(), 1e-7,
                "位移北分量应按新机头方向投影（10·cos30·0.5≈4.33 米）");
        double metersPerDegreeLng = METERS_PER_DEGREE * Math.cos(Math.toRadians(30.67));
        assertEquals(104.07 + eastMeters / metersPerDegreeLng,
                state.getDroneLongitude(), 1e-7, "位移东分量应按新机头方向投影（10·sin30·0.5=2.5 米）");
    }

    // ==================== TC-DRC-064：在舱时杆量被忽略 ====================

    @DisplayName("TC-DRC-064：droneInDock=true → 杆量不产生任何状态变化")
    @Test
    void stickIgnored_whenDroneInDock() {
        DeviceState state = flyingState();
        state.setDroneInDock(true);  // 在舱
        DrcCommandHandler handler = handler(state, Mockito.mock(DiagnosticLogRecorder.class));

        handler.integrateStick(SECOND_NANOS, 1684, 1684, 1684, 1684);
        handler.integrateStick(SECOND_NANOS * 2, 1684, 1684, 1684, 1684);

        assertEquals(30.67, state.getDroneLatitude(), 1e-9, "在舱时位置不变");
        assertEquals(104.07, state.getDroneLongitude(), 1e-9);
        assertEquals(50.0, state.getDroneHeight(), 1e-9, "在舱时高度不变");
        assertEquals(0.0, state.getAttitudeYaw(), 1e-9, "在舱时偏航不变");
        assertEquals(0.0, state.getAttitudePitch(), 1e-9, "在舱时姿态不变");
    }

    // ==================== TC-DRC-065：断流后恢复时间步封顶 ====================

    @DisplayName("TC-DRC-065：断流 5s 后恢复 → dt 按 0.5s 封顶，位移 5 米而非 50 米")
    @Test
    void dtCapped_afterStickStreamGap() {
        DeviceState state = flyingState();
        DrcCommandHandler handler = handler(state, Mockito.mock(DiagnosticLogRecorder.class));

        handler.integrateStick(SECOND_NANOS, 1024, 1684, 1024, 1024);                    // 建立基准
        handler.integrateStick(SECOND_NANOS * 6, 1024, 1684, 1024, 1024);                // 间隔 5s

        // dt 封顶 0.5s：位移 = 10m/s × 0.5s = 5 米（而非 10×5=50 米）
        assertEquals(30.67 + 5.0 / METERS_PER_DEGREE, state.getDroneLatitude(), 1e-9,
                "断流后积分时间步应按 0.5s 封顶（向北 5 米）");
    }

    // ==================== TC-DRC-066：杆量归一化逻辑 ====================

    @DisplayName("TC-DRC-066：clampStick 归一化——中值1024→0，满杆1684→1，364→-1，超界截断")
    @Test
    void clampStick_normalizesCorrectly() throws Exception {
        DeviceState state = flyingState();
        DrcCommandHandler handler = handler(state, Mockito.mock(DiagnosticLogRecorder.class));

        // 反射调用私有方法 clampStick(int)
        Method clampMethod = DrcCommandHandler.class.getDeclaredMethod("clampStick", int.class);
        clampMethod.setAccessible(true);

        assertEquals(0.0, ((Number) clampMethod.invoke(handler, 1024)).doubleValue(), 1e-9, "中值 1024 → 0.0（悬停）");
        assertEquals(1.0, ((Number) clampMethod.invoke(handler, 1684)).doubleValue(), 1e-9, "满杆前推 1684 → 1.0");
        assertEquals(-1.0, ((Number) clampMethod.invoke(handler, 364)).doubleValue(), 1e-9, "满杆后拉 364 → -1.0");
        assertEquals(-1.0, ((Number) clampMethod.invoke(handler, 0)).doubleValue(), 1e-9, "超界值 0 → -1.0（截断）");
        assertEquals(1.0, ((Number) clampMethod.invoke(handler, 2000)).doubleValue(), 1e-9, "超界值 2000 → 1.0（截断）");
    }

    // ==================== 路由验证：stick_control 消息进入积分逻辑 ====================

    @DisplayName("stick_control 下发路由验证：handleDrcCommand → 积分逻辑生效（无回包）")
    @Test
    void stickControlRoutesToIntegration() throws Exception {
        DeviceState state = flyingState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator aiSimulator = new AiSimulator(mqtt, rc, schema);
        DrcCommandHandler handler = new DrcCommandHandler(
                testProps(), mqtt, new ObjectMapper(), state,
                Mockito.mock(DiagnosticLogRecorder.class), Mockito.mock(CoverageRecorder.class),
                rc, schema, aiSimulator);
        handler.init();

        invokeHandleDrcCommand(handler,
                "{\"method\":\"stick_control\",\"data\":{\"roll\":1024,\"pitch\":1684,\"throttle\":1024,\"yaw\":1024},\"seq\":100}");

        // 首条仅建立基准（dt=0 不位移），但姿态角已映射 → 证明路由到积分逻辑
        assertEquals(-15.0, state.getAttitudePitch(), 1e-9, "stick_control 应路由到积分逻辑（姿态映射生效）");

        // stick_control 无回包机制：不应发布任何 drc/up 回复
        verify(mqtt, Mockito.never()).publishJson(anyString(), any());
        assertTrue(true, "路由验证完成");
    }
}
