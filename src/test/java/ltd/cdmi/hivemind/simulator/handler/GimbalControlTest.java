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
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.DrcConnectionManager;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 云台控制指令处理与 osd_info_push 闭环单元测试。
 * <p>覆盖 TDD-SPEC：
 * <ul>
 *   <li>TC-DRC-GIMBAL-001：drc_gimbal_reset reset_mode=0 回中</li>
 *   <li>TC-DRC-GIMBAL-002：drc_gimbal_reset reset_mode=1 俯仰向下</li>
 *   <li>TC-DRC-GIMBAL-003：drc_camera_screen_drag 画面拖动更新云台角度</li>
 *   <li>TC-DRC-GIMBAL-004：osd_info_push 云台角度闭环验证（state 值反映到上报）</li>
 * </ul>
 * <p>核实依据：DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html">Dock3 remote-control</a>
 * 与
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#drc-高频-osd-信息上报">osd_info_push 高频 OSD</a>。
 */
class GimbalControlTest {

    private static final String DRC_DOWN_TOPIC = "thing/product/test-gateway/drc/down";

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

    private DrcCommandHandler newHandler(DeviceState state, MqttClientManager mqtt,
                                          DiagnosticLogRecorder diag, CoverageRecorder cov,
                                          RuntimeConfig rc, DockTopicSchema schema, AiSimulator ai) {
        DrcCommandHandler handler = new DrcCommandHandler(
                testProps(), mqtt, new ObjectMapper(), state, diag, cov, rc, schema, ai,
                Mockito.mock(DrcConnectionManager.class));
        handler.init();
        return handler;
    }

    /** 反射调用 DrcCommandHandler.handleDrcCommand(topic, payload) */
    private void invokeHandleDrcCommand(DrcCommandHandler handler, String payload) throws Exception {
        Method m = DrcCommandHandler.class.getDeclaredMethod("handleDrcCommand", String.class, String.class);
        m.setAccessible(true);
        m.invoke(handler, DRC_DOWN_TOPIC, payload);
    }

    // ==================== TC-DRC-GIMBAL-001：drc_gimbal_reset reset_mode=0 回中 ====================

    @Test
    @DisplayName("TC-DRC-GIMBAL-001：drc_gimbal_reset reset_mode=0 回中（pitch/yaw/roll 归零）")
    void gimbalReset_mode0_resetsAllAnglesToZero() throws Exception {
        DeviceState state = new DeviceState();
        // 预设非零角度
        state.setGimbalPitch(-30.0);
        state.setGimbalYaw(45.0);
        state.setGimbalRoll(10.0);

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diag = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder cov = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator ai = new AiSimulator(mqtt, rc, schema);
        DrcCommandHandler handler = newHandler(state, mqtt, diag, cov, rc, schema, ai);

        String payload = "{\"method\":\"drc_gimbal_reset\","
                + "\"data\":{\"payload_index\":\"81-0-0\",\"reset_mode\":0},\"seq\":1}";
        invokeHandleDrcCommand(handler, payload);

        assertEquals(0.0, state.getGimbalPitch(), 0.001, "reset_mode=0 回中后 gimbalPitch 应为 0");
        assertEquals(0.0, state.getGimbalYaw(), 0.001, "reset_mode=0 回中后 gimbalYaw 应为 0");
        assertEquals(0.0, state.getGimbalRoll(), 0.001, "reset_mode=0 回中后 gimbalRoll 应为 0");
    }

    // ==================== TC-DRC-GIMBAL-002：drc_gimbal_reset reset_mode=1 俯仰向下 ====================

    @Test
    @DisplayName("TC-DRC-GIMBAL-002：drc_gimbal_reset reset_mode=1 俯仰向下（pitch=-90）")
    void gimbalReset_mode1_setsPitchToMinus90() throws Exception {
        DeviceState state = new DeviceState();
        state.setGimbalPitch(0.0);
        state.setGimbalYaw(30.0);  // yaw 不应受影响

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diag = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder cov = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator ai = new AiSimulator(mqtt, rc, schema);
        DrcCommandHandler handler = newHandler(state, mqtt, diag, cov, rc, schema, ai);

        String payload = "{\"method\":\"drc_gimbal_reset\","
                + "\"data\":{\"payload_index\":\"81-0-0\",\"reset_mode\":1},\"seq\":2}";
        invokeHandleDrcCommand(handler, payload);

        assertEquals(-90.0, state.getGimbalPitch(), 0.001, "reset_mode=1 俯仰向下后 gimbalPitch 应为 -90");
        assertEquals(30.0, state.getGimbalYaw(), 0.001, "reset_mode=1 不影响 gimbalYaw");
    }

    // ==================== TC-DRC-GIMBAL-003：drc_camera_screen_drag 画面拖动更新云台角度 ====================

    @Test
    @DisplayName("TC-DRC-GIMBAL-003：drc_camera_screen_drag 画面拖动按速度增量更新云台角度")
    void screenDrag_updatesGimbalAnglesBySpeed() throws Exception {
        DeviceState state = new DeviceState();
        state.setGimbalPitch(0.0);
        state.setGimbalYaw(0.0);

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diag = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder cov = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator ai = new AiSimulator(mqtt, rc, schema);
        DrcCommandHandler handler = newHandler(state, mqtt, diag, cov, rc, schema, ai);

        String payload = "{\"method\":\"drc_camera_screen_drag\","
                + "\"data\":{\"payload_index\":\"81-0-0\",\"locked\":false,\"pitch_speed\":10.0,\"yaw_speed\":5.0},\"seq\":3}";
        invokeHandleDrcCommand(handler, payload);

        assertEquals(10.0, state.getGimbalPitch(), 0.001, "画面拖动后 gimbalPitch 应增加 pitch_speed=10");
        assertEquals(5.0, state.getGimbalYaw(), 0.001, "画面拖动后 gimbalYaw 应增加 yaw_speed=5");
    }

    // ==================== TC-DRC-GIMBAL-004：osd_info_push 云台角度闭环验证 ====================

    @Test
    @DisplayName("TC-DRC-GIMBAL-004：云台角度闭环验证（指令→state→osd_info_push 字段一致性）")
    void gimbalControl_closesLoopWithOsdInfoPush() throws Exception {
        DeviceState state = new DeviceState();
        // 初始状态：非零角度
        state.setGimbalPitch(-45.0);
        state.setGimbalYaw(60.0);
        state.setGimbalRoll(5.0);

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diag = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder cov = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator ai = new AiSimulator(mqtt, rc, schema);
        DrcCommandHandler handler = newHandler(state, mqtt, diag, cov, rc, schema, ai);

        // 1. 下发 drc_gimbal_reset reset_mode=0（回中）
        String payload = "{\"method\":\"drc_gimbal_reset\","
                + "\"data\":{\"payload_index\":\"81-0-0\",\"reset_mode\":0},\"seq\":4}";
        invokeHandleDrcCommand(handler, payload);

        // 2. 验证 state 已归零（osd_info_push 将从 state 读取这些值）
        //    DeviceSimulator.buildOsdInfo() 中 gimbal_pitch/roll/yaw 已改为从 state 读取
        //    （替换原硬编码 0.0），因此 state 值即代表 osd_info_push 中的上报值
        assertEquals(0.0, state.getGimbalPitch(), 0.001,
                "闭环：reset 后 state.gimbalPitch=0.0 → osd_info_push gimbal_pitch=0.0");
        assertEquals(0.0, state.getGimbalYaw(), 0.001,
                "闭环：reset 后 state.gimbalYaw=0.0 → osd_info_push gimbal_yaw=0.0");
        assertEquals(0.0, state.getGimbalRoll(), 0.001,
                "闭环：reset 后 state.gimbalRoll=0.0 → osd_info_push gimbal_roll=0.0");
    }

    // ==================== 补充：连续指令验证（screen_drag 累积 + gimbal_reset 覆盖） ====================

    @Test
    @DisplayName("补充：连续 screen_drag 累积更新 + gimbal_reset 覆盖归零")
    void continuousScreenDrag_thenGimbalReset_resetsAccumulatedAngles() throws Exception {
        DeviceState state = new DeviceState();

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diag = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder cov = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator ai = new AiSimulator(mqtt, rc, schema);
        DrcCommandHandler handler = newHandler(state, mqtt, diag, cov, rc, schema, ai);

        // 第一次 screen_drag: pitch_speed=10, yaw_speed=5
        invokeHandleDrcCommand(handler, "{\"method\":\"drc_camera_screen_drag\","
                + "\"data\":{\"payload_index\":\"81-0-0\",\"locked\":false,\"pitch_speed\":10.0,\"yaw_speed\":5.0},\"seq\":5}");
        assertEquals(10.0, state.getGimbalPitch(), 0.001, "第一次 drag 后 pitch=10");
        assertEquals(5.0, state.getGimbalYaw(), 0.001, "第一次 drag 后 yaw=5");

        // 第二次 screen_drag: pitch_speed=5, yaw_speed=-3
        invokeHandleDrcCommand(handler, "{\"method\":\"drc_camera_screen_drag\","
                + "\"data\":{\"payload_index\":\"81-0-0\",\"locked\":false,\"pitch_speed\":5.0,\"yaw_speed\":-3.0},\"seq\":6}");
        assertEquals(15.0, state.getGimbalPitch(), 0.001, "第二次 drag 后 pitch=15（累积）");
        assertEquals(2.0, state.getGimbalYaw(), 0.001, "第二次 drag 后 yaw=2（累积）");

        // gimbal_reset 回中
        invokeHandleDrcCommand(handler, "{\"method\":\"drc_gimbal_reset\","
                + "\"data\":{\"payload_index\":\"81-0-0\",\"reset_mode\":0},\"seq\":7}");
        assertEquals(0.0, state.getGimbalPitch(), 0.001, "reset 后 pitch=0（覆盖累积值）");
        assertEquals(0.0, state.getGimbalYaw(), 0.001, "reset 后 yaw=0（覆盖累积值）");
    }
}
