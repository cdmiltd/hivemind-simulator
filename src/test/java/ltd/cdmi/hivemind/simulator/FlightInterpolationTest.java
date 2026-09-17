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

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * flyto/一键起飞位置连续插值（TC-FLY-033~035）单元测试。
 * <p>插值迭代 {@code advanceFlightInterpolation} 为纯计算方法，通过反射单步调用做确定性验证；
 * 辅以一个真实调度测试验证 fly_to_point 指令端到端插值到达。</p>
 */
class FlightInterpolationTest {

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
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockType()).thenReturn(DockModel.DOCK3);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK3-SN");
        Mockito.when(rc.getLocationLatitude()).thenReturn(30.67);
        Mockito.when(rc.getLocationLongitude()).thenReturn(104.07);
        Mockito.when(rc.getLocationHeight()).thenReturn(500.0);
        return rc;
    }

    private FlightCommandSimulator simulator(DeviceState state) {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        return new FlightCommandSimulator(
                testProps(), mqtt, state, runtimeConfig(),
                Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());
    }

    /** 反射调用 startFlightInterpolation */
    private void startInterp(FlightCommandSimulator sim, double lat, double lng, double elev, int maxSpeed)
            throws Exception {
        Method m = FlightCommandSimulator.class.getDeclaredMethod("startFlightInterpolation",
                double.class, double.class, double.class, int.class);
        m.setAccessible(true);
        m.invoke(sim, lat, lng, elev, maxSpeed);
    }

    /** 反射调用 advanceFlightInterpolation（单步推进） */
    private void advanceOnce(FlightCommandSimulator sim) throws Exception {
        Method m = FlightCommandSimulator.class.getDeclaredMethod("advanceFlightInterpolation");
        m.setAccessible(true);
        m.invoke(sim);
    }

    /** 反射调用 stopFlightInterpolation */
    private void stopInterp(FlightCommandSimulator sim) throws Exception {
        Method m = FlightCommandSimulator.class.getDeclaredMethod("stopFlightInterpolation");
        m.setAccessible(true);
        m.invoke(sim);
    }

    /** 反射读取 interpActive */
    private boolean interpActive(FlightCommandSimulator sim) throws Exception {
        Field f = FlightCommandSimulator.class.getDeclaredField("interpActive");
        f.setAccessible(true);
        return f.getBoolean(sim);
    }

    /** 飞行中状态：位置 (30.67, 104.07)，椭球高 500 */
    private DeviceState flyingState() {
        DeviceState state = new DeviceState();
        state.setDroneActivated(true);
        state.setDroneInDock(false);
        state.setDroneLatitude(30.67);
        state.setDroneLongitude(104.07);
        state.setDroneElevation(500.0);
        state.setDroneHeight(0.0);
        state.setSimulateMissionEnable(0);
        return state;
    }

    // ==================== TC-FLY-033：flyto 连续插值 ====================

    @DisplayName("TC-FLY-033：单步推进 = max_speed × 0.5s（10m/s → 5 米），垂直 3m/s → 1.5 米")
    @Test
    void singleStep_advancesBySpeedTimesInterval() throws Exception {
        DeviceState state = flyingState();
        FlightCommandSimulator sim = simulator(state);
        // 目标：正北 30 米、椭球高 506（+6 米）——多步才能到达
        startInterp(sim, 30.67 + 30.0 / METERS_PER_DEGREE, 104.07, 506.0, 10);

        advanceOnce(sim);
        assertEquals(30.67 + 5.0 / METERS_PER_DEGREE, state.getDroneLatitude(), 1e-9,
                "单步应向北推进 5 米（10m/s × 0.5s）");
        assertEquals(104.07, state.getDroneLongitude(), 1e-9, "正北方向经度不变");
        assertEquals(501.5, state.getDroneElevation(), 1e-9, "单步椭球高应推进 1.5 米（3m/s × 0.5s）");
        assertEquals(1.5, state.getDroneHeight(), 1e-9, "相对高度 = 椭球高 - 机场海拔");
        assertTrue(interpActive(sim), "未到达目标，插值应继续");
    }

    @DisplayName("TC-FLY-033：到达目标点后插值自动停止，位置精确落位不越过")
    @Test
    void arrival_stopsInterpolationAndSnapsToTarget() throws Exception {
        DeviceState state = flyingState();
        FlightCommandSimulator sim = simulator(state);
        // 目标：正北 3 米（< 5 米步长）、椭球高 501（+1 米 < 1.5 米步长）→ 单步即达
        double targetLat = 30.67 + 3.0 / METERS_PER_DEGREE;
        startInterp(sim, targetLat, 104.07, 501.0, 10);

        advanceOnce(sim);
        assertEquals(targetLat, state.getDroneLatitude(), 1e-12, "应精确置于目标点（不越过）");
        assertEquals(501.0, state.getDroneElevation(), 1e-12, "椭球高精确置于目标值");
        assertEquals(1.0, state.getDroneHeight(), 1e-12, "相对高度 = 501-500");
        assertFalse(interpActive(sim), "到达目标点后插值应自动停止");

        // 再次推进：插值已停止，位置保持
        advanceOnce(sim);
        assertEquals(targetLat, state.getDroneLatitude(), 1e-12, "停止后位置不应变化");
    }

    // ==================== TC-FLY-035：stop 悬停当前位置 ====================

    @DisplayName("TC-FLY-035：飞行中 stop → 悬停在当前位置（非起点非目标），后续保持不变")
    @Test
    void stopDuringFlight_hoversAtCurrentPosition() throws Exception {
        DeviceState state = flyingState();
        FlightCommandSimulator sim = simulator(state);
        double targetLat = 30.67 + 30.0 / METERS_PER_DEGREE;
        startInterp(sim, targetLat, 104.07, 506.0, 10);

        advanceOnce(sim);  // 推进 5 米（中途）
        double hoveredLat = state.getDroneLatitude();
        assertTrue(hoveredLat > 30.67, "中途位置应已离开起点");
        assertTrue(hoveredLat < targetLat, "中途位置不应到达目标");

        stopInterp(sim);
        advanceOnce(sim);  // 停止后迭代应空转
        assertEquals(hoveredLat, state.getDroneLatitude(), 1e-12, "stop 后位置应悬停不变");
        assertEquals(501.5, state.getDroneElevation(), 1e-12, "stop 后高度应悬停不变");
    }

    // ==================== 端到端：fly_to_point 指令驱动插值到达 ====================

    @DisplayName("TC-FLY-033 端到端：fly_to_point 指令 → 插值连续推进并到达目标点")
    @Test
    void flyToPointCommand_interpolatesToTarget() throws Exception {
        DeviceState state = flyingState();
        state.setDroneLatitude(22.0);
        state.setDroneLongitude(113.0);
        state.setDroneElevation(500.0);
        FlightCommandSimulator sim = simulator(state);

        // 目标：正北约 10 米、椭球高 503（+3 米）；max_speed=10 → 水平 1s、垂直 1s 到达
        double targetLat = 22.0 + 10.0 / METERS_PER_DEGREE;
        String json = """
                {
                  "fly_to_id": "FLY-INTERP",
                  "points": [{"latitude": %s, "longitude": 113.0, "height": 503}],
                  "max_speed": 10
                }
                """.formatted(targetLat);
        sim.handleFlyToPoint(new ObjectMapper().readTree(json), "bid-interp");

        // 中途快照（0.6s）：插值已推进约 1 步（5 米），介于起点与目标之间（连续非瞬移）
        Thread.sleep(600);
        assertTrue(state.getDroneLatitude() > 22.0, "中途位置应已离开起点（连续推进）");
        assertTrue(state.getDroneLatitude() < targetLat, "中途位置不应瞬移到目标");

        // 到达（水平 1s + 垂直 1s + 余量）
        Thread.sleep(1500);
        assertEquals(targetLat, state.getDroneLatitude(), 1e-9, "应到达目标纬度");
        assertEquals(503.0, state.getDroneElevation(), 0.001, "应到达目标椭球高");
        assertEquals(3.0, state.getDroneHeight(), 0.001, "相对高度 = 503-500");
    }
}
