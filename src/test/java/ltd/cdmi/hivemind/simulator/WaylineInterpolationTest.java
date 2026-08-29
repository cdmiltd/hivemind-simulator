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
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航线任务位置连续插值（TC-WAYLINE-024~026）单元测试。
 * <p>验证：步骤间匀速推进（水平 5 米/次、垂直 1.5 米/次）、距离不足步长时精确定位、
 * 任务完成/取消后插值停止。</p>
 * <p>插值迭代 {@code advanceInterpolation} 为纯计算方法（读 state 写 state），
 * 测试通过反射单次调用验证数值，不依赖调度器时序。</p>
 */
class WaylineInterpolationTest {

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

    /** 构造 WaylineTaskSimulator 并通过反射设置当前任务（插值运行前置条件） */
    @SuppressWarnings("unchecked")
    private WaylineTaskSimulator simulatorWithTask(DeviceState state) throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        MediaUploadSimulator mediaUpload = Mockito.mock(MediaUploadSimulator.class);

        WaylineTaskSimulator simulator = new WaylineTaskSimulator(
                testProps(), mqtt, state, new ObjectMapper(), commandHandler, mediaUpload,
                runtimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        // 反射设置 currentFlightId（插值迭代前置条件）
        Field flightIdField = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        flightIdField.setAccessible(true);
        flightIdField.set(simulator, "FLIGHT-INTERP");
        return simulator;
    }

    /** 反射调用 advanceInterpolation（单次插值迭代） */
    private void advanceOnce(WaylineTaskSimulator simulator) throws Exception {
        Method m = WaylineTaskSimulator.class.getDeclaredMethod("advanceInterpolation");
        m.setAccessible(true);
        m.invoke(simulator);
    }

    /** 反射调用 startInterpolation（设定目标点，不启动调度器时目标字段已就绪） */
    private void startInterpolation(WaylineTaskSimulator simulator, double lat, double lng, double height)
            throws Exception {
        Method m = WaylineTaskSimulator.class.getDeclaredMethod(
                "startInterpolation", double.class, double.class, double.class);
        m.setAccessible(true);
        m.invoke(simulator, lat, lng, height);
    }

    /** 反射调用 stopInterpolation */
    private void stopInterpolation(WaylineTaskSimulator simulator) throws Exception {
        Method m = WaylineTaskSimulator.class.getDeclaredMethod("stopInterpolation");
        m.setAccessible(true);
        m.invoke(simulator);
    }

    /** 反射读取 interpTask 是否为 null（验证调度器生命周期） */
    private boolean interpTaskRunning(WaylineTaskSimulator simulator) throws Exception {
        Field f = WaylineTaskSimulator.class.getDeclaredField("interpTask");
        f.setAccessible(true);
        AtomicReference<?> ref = (AtomicReference<?>) f.get(simulator);
        return ref.get() != null;
    }

    // ==================== TC-WAYLINE-024：步骤间匀速推进 ====================

    @DisplayName("TC-WAYLINE-024：单次迭代向北推进 5 米、高度推进 1.5 米（10m/s、3m/s × 0.5s）")
    @Test
    void singleIteration_advancesFiveMetersNorth() throws Exception {
        DeviceState state = new DeviceState();
        state.setDroneLatitude(30.67);
        state.setDroneLongitude(104.07);
        state.setDroneHeight(0.0);
        state.setDroneElevation(500.0);

        WaylineTaskSimulator simulator = simulatorWithTask(state);
        // 目标：正北 30 米、高度 12 米（多步才能到达，验证单步步长）
        startInterpolation(simulator, 30.67 + 30.0 / METERS_PER_DEGREE, 104.07, 12.0);
        advanceOnce(simulator);

        assertEquals(30.67 + 5.0 / METERS_PER_DEGREE, state.getDroneLatitude(), 1e-9,
                "单次迭代应向北推进 5 米（10m/s × 0.5s）");
        assertEquals(104.07, state.getDroneLongitude(), 1e-9, "正北方向经度不变");
        assertEquals(1.5, state.getDroneHeight(), 1e-9, "单次迭代高度应推进 1.5 米（3m/s × 0.5s）");
        assertEquals(500.0 + 1.5, state.getDroneElevation(), 1e-9, "elevation = 机场海拔 + 相对高度");
    }

    @DisplayName("TC-WAYLINE-024：多步迭代逐步逼近目标点（无瞬移跳变）")
    @Test
    void multipleIterations_convergeToTargetWithoutJump() throws Exception {
        DeviceState state = new DeviceState();
        state.setDroneLatitude(30.67);
        state.setDroneLongitude(104.07);
        state.setDroneHeight(0.0);

        WaylineTaskSimulator simulator = simulatorWithTask(state);
        double targetLat = 30.67 + 12.0 / METERS_PER_DEGREE;  // 正北 12 米（2 步 + 2 米余量）
        startInterpolation(simulator, targetLat, 104.07, 0.0);

        advanceOnce(simulator);
        double afterFirst = state.getDroneLatitude();
        assertEquals(30.67 + 5.0 / METERS_PER_DEGREE, afterFirst, 1e-9, "第 1 步推进 5 米");

        advanceOnce(simulator);
        assertEquals(30.67 + 10.0 / METERS_PER_DEGREE, state.getDroneLatitude(), 1e-9,
                "第 2 步累计推进 10 米（连续推进而非跳变到目标）");

        // 每步位移 5 米 < 12 米总距离，中途未越过目标
        assertTrue(afterFirst < targetLat, "中途位置不应越过目标点");
    }

    // ==================== TC-WAYLINE-025：距离不足步长时精确定位 ====================

    @DisplayName("TC-WAYLINE-025：距目标 3 米（<5 米步长）→ 精确置于目标点，不越界不震荡")
    @Test
    void remainingDistanceLessThanStep_snapsToTarget() throws Exception {
        DeviceState state = new DeviceState();
        state.setDroneLatitude(30.67);
        state.setDroneLongitude(104.07);
        state.setDroneHeight(0.0);

        WaylineTaskSimulator simulator = simulatorWithTask(state);
        double targetLat = 30.67 + 3.0 / METERS_PER_DEGREE;  // 正北 3 米 < 5 米步长
        startInterpolation(simulator, targetLat, 104.07, 1.0);  // 高度差 1 米 < 1.5 米步长

        advanceOnce(simulator);
        assertEquals(targetLat, state.getDroneLatitude(), 1e-12, "应精确置于目标点（不越过）");
        assertEquals(1.0, state.getDroneHeight(), 1e-12, "高度精确置于目标值");

        // 再次迭代：已在目标点，保持不动（不震荡）
        advanceOnce(simulator);
        assertEquals(targetLat, state.getDroneLatitude(), 1e-12, "到达后再次迭代应保持不动");
        assertEquals(1.0, state.getDroneHeight(), 1e-12);
    }

    // ==================== TC-WAYLINE-026：任务完成/取消后插值停止 ====================

    @DisplayName("TC-WAYLINE-026：stopInterpolation 后目标清除、迭代空转")
    @Test
    void stopInterpolation_clearsTargetAndIdle() throws Exception {
        DeviceState state = new DeviceState();
        state.setDroneLatitude(30.67);
        state.setDroneLongitude(104.07);
        state.setDroneHeight(0.0);

        WaylineTaskSimulator simulator = simulatorWithTask(state);
        startInterpolation(simulator, 30.67 + 30.0 / METERS_PER_DEGREE, 104.07, 12.0);
        advanceOnce(simulator);  // 推进 5 米
        double advanced = state.getDroneLatitude();

        stopInterpolation(simulator);
        assertFalse(interpTaskRunning(simulator), "插值调度器应已取消");

        advanceOnce(simulator);  // 停止后迭代应空转
        assertEquals(advanced, state.getDroneLatitude(), 1e-12, "停止后位置不应再变化");
    }

    @DisplayName("TC-WAYLINE-026：调度器生命周期——start 启动、stop 取消")
    @Test
    void interpolationScheduler_lifecycleManaged() throws Exception {
        DeviceState state = new DeviceState();
        state.setDroneLatitude(30.67);
        state.setDroneLongitude(104.07);
        state.setDroneHeight(0.0);

        WaylineTaskSimulator simulator = simulatorWithTask(state);
        assertFalse(interpTaskRunning(simulator), "初始状态插值调度器不应运行");

        startInterpolation(simulator, 30.67, 104.07, 10.0);
        assertTrue(interpTaskRunning(simulator), "startInterpolation 后调度器应运行");

        stopInterpolation(simulator);
        assertFalse(interpTaskRunning(simulator), "stopInterpolation 后调度器应取消");
    }
}
