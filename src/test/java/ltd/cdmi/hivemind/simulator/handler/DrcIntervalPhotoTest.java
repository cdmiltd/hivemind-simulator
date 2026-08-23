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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 定时拍照间隔指令（drc_interval_photo_set）处理与状态上报闭环单元测试。
 * <p>覆盖 TDD-SPEC：
 * <ul>
 *   <li>TC-DRC-054：drc_interval_photo_set 指令处理（interval 字符串 → state.double）</li>
 *   <li>TC-DRC-055：定时拍照间隔→camera_state_push 闭环验证（指令 → state → 上报）</li>
 * </ul>
 * <p>核实依据：DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#drc-定时拍照">Dock3 DRC 定时拍照</a>
 * 与
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#相机状态推送">相机状态推送</a>。
 */
class DrcIntervalPhotoTest {

    private static final String DRC_DOWN_TOPIC = "thing/product/test-gateway/drc/down";

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

    // ==================== TC-DRC-054：drc_interval_photo_set 指令处理 ====================

    @DisplayName("TC-DRC-054：drc_interval_photo_set interval=\"5.0\" 更新 state 且回复 result=0")
    @Test
    void intervalPhotoSet_updatesStateAndRepliesResult0() throws Exception {
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

        // 默认值校验
        assertEquals(2.5, state.getIntervalPhotoInterval(),
                "默认 intervalPhotoInterval 应为 2.5（对齐 DJI 文档示例值）");

        // 下发指令 interval="5.0"（字符串类型，DJI 文档定义）
        String payload = "{\"method\":\"drc_interval_photo_set\","
                + "\"data\":{\"payload_index\":\"81-0-0\",\"interval\":\"5.0\"},\"seq\":54}";
        invokeHandleDrcCommand(handler, payload);

        // state 字段已更新（字符串 "5.0" → double 5.0）
        assertEquals(5.0, state.getIntervalPhotoInterval(),
                "intervalPhotoInterval 应更新为 5.0");

        // 回复：method 回显 + result=0 + seq 回显
        Map<String, Object> reply = captureLastPublish(mqtt);
        assertEquals("drc_interval_photo_set", reply.get("method"),
                "回复应回显 method: " + reply);
        assertEquals(54, reply.get("seq"),
                "回复应回显 seq=54: " + reply);
        @SuppressWarnings("unchecked")
        Map<String, Object> replyData = (Map<String, Object>) reply.get("data");
        assertEquals(0, replyData.get("result"),
                "回复应为 result=0: " + reply);
    }

    // ==================== TC-DRC-055：定时拍照间隔→camera_state_push 闭环验证 ====================

    @DisplayName("TC-DRC-055：drc_interval_photo_set interval=\"5.0\" → camera_state_push 中 interval_photo_interval=5.0")
    @Test
    void intervalPhotoCommand_closesLoopWithCameraStatePush() throws Exception {
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

        // 1. 平台下发 drc_interval_photo_set interval="5.0"
        String payload = "{\"method\":\"drc_interval_photo_set\","
                + "\"data\":{\"payload_index\":\"81-0-0\",\"interval\":\"5.0\"},\"seq\":55}";
        invokeHandleDrcCommand(handler, payload);

        // 2. 验证 state 已更新
        assertEquals(5.0, state.getIntervalPhotoInterval(),
                "指令下发后 state.intervalPhotoInterval 应为 5.0");

        // 3. 触发 buildDrcCameraState（反射调用，验证闭环）
        Mockito.clearInvocations(mqtt);  // 清除指令回复的 publishJson 调用
        Method m = DeviceSimulator.class.getDeclaredMethod("buildDrcCameraState");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> cameraStatePush = (Map<String, Object>) m.invoke(simulator);

        // 4. 验证 camera_state_push 中 interval_photo_interval=5.0（闭环）
        assertNotNull(cameraStatePush, "buildDrcCameraState 返回不应为 null");
        @SuppressWarnings("unchecked")
        Map<String, Object> cameraState = (Map<String, Object>) cameraStatePush.get("camera_state");
        assertEquals(5.0, cameraState.get("interval_photo_interval"),
                "闭环验证：camera_state_push 中 interval_photo_interval 应为 5.0（反映指令变更）");
    }

    private static void assertNotNull(Object actual, String message) {
        org.junit.jupiter.api.Assertions.assertNotNull(actual, message);
    }
}
