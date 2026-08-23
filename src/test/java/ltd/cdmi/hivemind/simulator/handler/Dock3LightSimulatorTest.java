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
import ltd.cdmi.hivemind.simulator.device.DeviceSimulator;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Dock3 探照灯（AL1）指令处理与状态上报单元测试。
 * <p>覆盖 TDD-SPEC：
 * <ul>
 *   <li>TC-DRC-025~028：探照灯 4 个 DRC 指令处理（亮度/模式/微调/校准）</li>
 *   <li>TC-DRC-035：drc_psdk_state_info 事件推送（探照灯状态字段集）</li>
 *   <li>TC-DRC-043：探照灯亮度指令→PSDK 状态闭环验证</li>
 * </ul>
 * <p>核实依据：DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html">Dock3 远程控制（探照灯 AL1）</a>。
 */
class Dock3LightSimulatorTest {

    private static final String DRC_DOWN_TOPIC = "thing/product/test-gateway/drc/down";
    private static final String DRC_UP_TOPIC = "thing/product/test-gateway/drc/up";

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null);
    }

    private RuntimeConfig runtimeConfig() {
        RuntimeConfig rc = new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
        rc.setDockType(DockModel.DOCK3);
        return rc;
    }

    /** 构造 DrcCommandHandler + DeviceState 共享实例，用于指令处理测试 */
    private static class HandlerCtx {
        final DrcCommandHandler handler;
        final DeviceState state;
        final MqttClientManager mqtt;

        HandlerCtx(DrcCommandHandler handler, DeviceState state, MqttClientManager mqtt) {
            this.handler = handler;
            this.state = state;
            this.mqtt = mqtt;
        }
    }

    private HandlerCtx newHandlerCtx() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diagnosticRecorder = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder coverageRecorder = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator aiSimulator = new AiSimulator(mqtt, rc, schema);
        DeviceState state = new DeviceState();
        DrcCommandHandler handler = new DrcCommandHandler(
                testProps(), mqtt, new ObjectMapper(), state,
                diagnosticRecorder, coverageRecorder, rc, schema, aiSimulator);
        handler.init();
        return new HandlerCtx(handler, state, mqtt);
    }

    /** 反射调用 DrcCommandHandler.handleDrcCommand(topic, payload) */
    private void invokeHandleDrcCommand(DrcCommandHandler handler, String payload) throws Exception {
        Method m = DrcCommandHandler.class.getDeclaredMethod("handleDrcCommand", String.class, String.class);
        m.setAccessible(true);
        m.invoke(handler, DRC_DOWN_TOPIC, payload);
    }

    /** 捕获最近一次 publishJson 调用的 Map 参数 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> captureLastPublish(MqttClientManager mqtt) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mqtt, atLeastOnce()).publishJson(anyString(), captor.capture());
        return captor.getValue();
    }

    // ==================== TC-DRC-025：drc_light_brightness_set（亮度设置） ====================

    @DisplayName("TC-DRC-025：drc_light_brightness_set brightness=80 更新 state 且回复 result=0")
    @Test
    void lightBrightnessSet_updatesStateAndRepliesResult0() throws Exception {
        HandlerCtx ctx = newHandlerCtx();
        String payload = "{\"method\":\"drc_light_brightness_set\","
                + "\"data\":{\"psdk_index\":1,\"group\":0,\"brightness\":80},\"seq\":25}";
        invokeHandleDrcCommand(ctx.handler, payload);

        // state 字段已更新
        assertEquals(80, ctx.state.getLightBrightness(),
                "lightBrightness 应更新为 80");

        // 回复：method 回显 + result=0 + seq 回显
        Map<String, Object> reply = captureLastPublish(ctx.mqtt);
        assertEquals("drc_light_brightness_set", reply.get("method"),
                "回复应回显 method: " + reply);
        assertEquals(25, reply.get("seq"),
                "回复应回显 seq=25: " + reply);
        @SuppressWarnings("unchecked")
        Map<String, Object> replyData = (Map<String, Object>) reply.get("data");
        assertEquals(0, replyData.get("result"),
                "回复应为 result=0: " + reply);
    }

    // ==================== TC-DRC-026：drc_light_mode_set（模式设置） ====================

    @DisplayName("TC-DRC-026：drc_light_mode_set mode=2 更新 state 且回复 result=0")
    @Test
    void lightModeSet_updatesStateAndRepliesResult0() throws Exception {
        HandlerCtx ctx = newHandlerCtx();
        String payload = "{\"method\":\"drc_light_mode_set\","
                + "\"data\":{\"psdk_index\":1,\"group\":0,\"mode\":2},\"seq\":26}";
        invokeHandleDrcCommand(ctx.handler, payload);

        // state 字段已更新（2=爆闪）
        assertEquals(2, ctx.state.getLightMode(),
                "lightMode 应更新为 2（爆闪）");

        // 回复
        Map<String, Object> reply = captureLastPublish(ctx.mqtt);
        assertEquals("drc_light_mode_set", reply.get("method"));
        assertEquals(26, reply.get("seq"));
        @SuppressWarnings("unchecked")
        Map<String, Object> replyData = (Map<String, Object>) reply.get("data");
        assertEquals(0, replyData.get("result"));
    }

    // ==================== TC-DRC-027：drc_light_fine_tuning_set（左右角度微调） ====================

    @DisplayName("TC-DRC-027a：drc_light_fine_tuning_set position=0 更新左灯角度")
    @Test
    void lightFineTuningSet_leftLight_updatesLeftAngle() throws Exception {
        HandlerCtx ctx = newHandlerCtx();
        String payload = "{\"method\":\"drc_light_fine_tuning_set\","
                + "\"data\":{\"psdk_index\":1,\"position\":0,\"value\":10,\"saved\":false},\"seq\":27}";
        invokeHandleDrcCommand(ctx.handler, payload);

        assertEquals(10, ctx.state.getLightLeftAngle(),
                "position=0 应更新左灯角度 lightLeftAngle=10");
        // 右灯角度不受影响
        assertEquals(0, ctx.state.getLightRightAngle(),
                "右灯角度不应受 position=0 指令影响");

        Map<String, Object> reply = captureLastPublish(ctx.mqtt);
        assertEquals("drc_light_fine_tuning_set", reply.get("method"));
        assertEquals(27, reply.get("seq"));
    }

    @DisplayName("TC-DRC-027b：drc_light_fine_tuning_set position=1 更新右灯角度")
    @Test
    void lightFineTuningSet_rightLight_updatesRightAngle() throws Exception {
        HandlerCtx ctx = newHandlerCtx();
        String payload = "{\"method\":\"drc_light_fine_tuning_set\","
                + "\"data\":{\"psdk_index\":1,\"position\":1,\"value\":20,\"saved\":false},\"seq\":28}";
        invokeHandleDrcCommand(ctx.handler, payload);

        assertEquals(20, ctx.state.getLightRightAngle(),
                "position=1 应更新右灯角度 lightRightAngle=20");
        // 左灯角度不受影响
        assertEquals(0, ctx.state.getLightLeftAngle(),
                "左灯角度不应受 position=1 指令影响");

        Map<String, Object> reply = captureLastPublish(ctx.mqtt);
        assertEquals("drc_light_fine_tuning_set", reply.get("method"));
    }

    // ==================== TC-DRC-028：drc_light_calibration（云台校准） ====================

    @DisplayName("TC-DRC-028：drc_light_calibration 回复 result=0，无 state 字段变化")
    @Test
    void lightCalibration_repliesResult0NoStateChange() throws Exception {
        HandlerCtx ctx = newHandlerCtx();
        // 记录指令前 state 字段值（应为默认值）
        int brightnessBefore = ctx.state.getLightBrightness();
        int modeBefore = ctx.state.getLightMode();

        String payload = "{\"method\":\"drc_light_calibration\","
                + "\"data\":{\"psdk_index\":1},\"seq\":29}";
        invokeHandleDrcCommand(ctx.handler, payload);

        // 校准无 state 变化
        assertEquals(brightnessBefore, ctx.state.getLightBrightness(),
                "云台校准不应修改 lightBrightness");
        assertEquals(modeBefore, ctx.state.getLightMode(),
                "云台校准不应修改 lightMode");

        Map<String, Object> reply = captureLastPublish(ctx.mqtt);
        assertEquals("drc_light_calibration", reply.get("method"));
        assertEquals(29, reply.get("seq"));
        @SuppressWarnings("unchecked")
        Map<String, Object> replyData = (Map<String, Object>) reply.get("data");
        assertEquals(0, replyData.get("result"));
    }

    // ==================== TC-DRC-035：drc_psdk_state_info 事件推送（探照灯状态字段集） ====================

    @DisplayName("TC-DRC-035：publishPsdkAndAiEvents 推送探照灯 drc_psdk_state_info 含完整字段集")
    @Test
    void publishPsdkAndAiEvents_pushesLightStateWithFullFieldSet() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = new DeviceState();
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator aiSimulator = new AiSimulator(mqtt, rc, schema);
        DiagnosticLogRecorder diagnosticRecorder = Mockito.mock(DiagnosticLogRecorder.class);

        // 预设 state 值，便于后续断言
        state.setLightBrightness(80);
        state.setLightMode(2);  // 爆闪
        state.setLightLeftAngle(10);
        state.setLightRightAngle(20);

        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, new ObjectMapper(), rc,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                diagnosticRecorder, schema, aiSimulator);

        // 反射调用 publishPsdkAndAiEvents(drcUpTopic)
        Method m = DeviceSimulator.class.getDeclaredMethod("publishPsdkAndAiEvents", String.class);
        m.setAccessible(true);
        m.invoke(simulator, DRC_UP_TOPIC);

        // 捕获所有 publishJson 调用，找到探照灯的 drc_psdk_state_info（psdk_index=1）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mqtt, atLeastOnce()).publishJson(anyString(), captor.capture());
        Map<String, Object> lightEvent = null;
        for (Map<String, Object> event : captor.getAllValues()) {
            if (!"drc_psdk_state_info".equals(event.get("method"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) event.get("data");
            if (Integer.valueOf(1).equals(data.get("psdk_index"))) {
                lightEvent = data;
                break;
            }
        }
        assertNotNull(lightEvent, "应推送 psdk_index=1 的探照灯 drc_psdk_state_info");

        // 顶层设备标识字段
        assertEquals(1, lightEvent.get("psdk_index"), "psdk_index=1（探照灯）");
        assertEquals(5, lightEvent.get("psdk_type"), "psdk_type=5（大疆自研）");
        assertEquals("Searchlight", lightEvent.get("psdk_name"), "psdk_name=Searchlight");
        assertNotNull(lightEvent.get("psdk_sn"), "psdk_sn 不应为 null");
        assertNotNull(lightEvent.get("psdk_version"), "psdk_version 不应为 null");
        assertNotNull(lightEvent.get("psdk_lib_version"), "psdk_lib_version 不应为 null");

        // light 子对象字段（应反映 state 当前值）
        @SuppressWarnings("unchecked")
        Map<String, Object> light = (Map<String, Object>) lightEvent.get("light");
        assertNotNull(light, "light 子对象不应为 null");
        assertEquals(2, light.get("work_mode"), "work_mode 应反映 state.lightMode=2");
        assertEquals(80, light.get("brightness"), "brightness 应反映 state.lightBrightness=80");
        assertEquals(0, light.get("calibration_status"), "calibration_status=0（校准完成）");
        assertEquals(100, light.get("calibration_progress"), "calibration_progress=100");
        assertEquals(10, light.get("left_value"), "left_value 应反映 state.lightLeftAngle=10");
        assertEquals(20, light.get("right_value"), "right_value 应反映 state.lightRightAngle=20");
        assertEquals(false, light.get("wide_field_mode"), "wide_field_mode=false");
        assertEquals(false, light.get("light_gimbal_control"), "light_gimbal_control=false");
    }

    // ==================== TC-DRC-043：探照灯亮度指令→PSDK 状态闭环验证 ====================

    @DisplayName("TC-DRC-043：drc_light_brightness_set brightness=80 → 下一周期 drc_psdk_state_info 中 light.brightness=80")
    @Test
    void lightBrightnessCommand_closesLoopWithPsdkStateInfo() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diagnosticRecorder = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder coverageRecorder = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator aiSimulator = new AiSimulator(mqtt, rc, schema);
        DeviceState state = new DeviceState();

        // 复用同一 DeviceState 实例构造 DrcCommandHandler（接收指令）+ DeviceSimulator（推送状态）
        DrcCommandHandler handler = new DrcCommandHandler(
                testProps(), mqtt, new ObjectMapper(), state,
                diagnosticRecorder, coverageRecorder, rc, schema, aiSimulator);
        handler.init();

        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, new ObjectMapper(), rc,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                diagnosticRecorder, schema, aiSimulator);

        // 1. 平台下发 drc_light_brightness_set brightness=80
        String payload = "{\"method\":\"drc_light_brightness_set\","
                + "\"data\":{\"psdk_index\":1,\"group\":0,\"brightness\":80},\"seq\":43}";
        invokeHandleDrcCommand(handler, payload);

        // 2. 验证 state 已更新
        assertEquals(80, state.getLightBrightness(),
                "指令下发后 state.lightBrightness 应为 80");

        // 3. 触发下一个推送周期（反射调用 publishPsdkAndAiEvents）
        Mockito.clearInvocations(mqtt);  // 清除指令回复的 publishJson 调用，便于后续断言
        Method m = DeviceSimulator.class.getDeclaredMethod("publishPsdkAndAiEvents", String.class);
        m.setAccessible(true);
        m.invoke(simulator, DRC_UP_TOPIC);

        // 4. 验证 drc_psdk_state_info 中 light.brightness=80（闭环）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mqtt, atLeastOnce()).publishJson(anyString(), captor.capture());
        int brightnessInPush = -1;
        for (Map<String, Object> event : captor.getAllValues()) {
            if (!"drc_psdk_state_info".equals(event.get("method"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) event.get("data");
            if (!Integer.valueOf(1).equals(data.get("psdk_index"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> light = (Map<String, Object>) data.get("light");
            if (light != null && light.get("brightness") != null) {
                brightnessInPush = (Integer) light.get("brightness");
                break;
            }
        }
        assertEquals(80, brightnessInPush,
                "闭环验证：drc_psdk_state_info 中 light.brightness 应为 80（反映指令变更）");
    }
}
