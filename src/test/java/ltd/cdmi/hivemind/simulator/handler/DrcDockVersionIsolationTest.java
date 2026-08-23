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
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.DrcMethod;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dock 版本隔离测试：验证 Dock3 专属 DRC 指令仅在 Dock3 注册，Dock1/Dock2 不注册、收到时走兜底。
 * <p>对齐 TDD-SPEC TC-DRC-DOCK3-001~003。
 * <p>核实依据：SDK {@link DrcMethod} 类注释明确 24 个指令为 Dock3 独有
 * （探照灯/相机 8 个 + 喊话器 5 个 + AI 11 个）。
 */
class DrcDockVersionIsolationTest {

    /** Dock3 专属 DRC 指令（24 个），来源 SDK DrcMethod 类注释"Dock3 独有 13 个 + Dock3 AI 11 个" */
    private static final List<String> DOCK3_METHODS = List.of(
            // 探照灯/相机 8 个
            DrcMethod.DRC_CAMERA_NIGHT_MODE_SET.methodName(),
            DrcMethod.DRC_CAMERA_DENOISE_LEVEL_SET.methodName(),
            DrcMethod.DRC_CAMERA_NIGHT_VISION_ENABLE.methodName(),
            DrcMethod.DRC_INFRARED_FILL_LIGHT_ENABLE.methodName(),
            DrcMethod.DRC_LIGHT_BRIGHTNESS_SET.methodName(),
            DrcMethod.DRC_LIGHT_MODE_SET.methodName(),
            DrcMethod.DRC_LIGHT_FINE_TUNING_SET.methodName(),
            DrcMethod.DRC_LIGHT_CALIBRATION.methodName(),
            // 喊话器 5 个
            DrcMethod.DRC_SPEAKER_PLAY_MODE_SET.methodName(),
            DrcMethod.DRC_SPEAKER_TTS_SET.methodName(),
            DrcMethod.DRC_SPEAKER_PLAY_VOLUME_SET.methodName(),
            DrcMethod.DRC_SPEAKER_PLAY_STOP.methodName(),
            DrcMethod.DRC_SPEAKER_REPLAY.methodName(),
            // AI 11 个
            DrcMethod.DRC_AI_MODEL_SELECT.methodName(),
            DrcMethod.DRC_AI_IDENTIFY_SET.methodName(),
            DrcMethod.DRC_AI_IDENTIFY_SCORE_MODE_SET.methodName(),
            DrcMethod.DRC_AI_IDENTIFY_SCORE_SET.methodName(),
            DrcMethod.DRC_AI_IDENTIFY_SCORE_RESET.methodName(),
            DrcMethod.DRC_AI_IDENTIFY_FILTER_SET.methodName(),
            DrcMethod.DRC_AI_SPOTLIGHT_ZOOM_SET.methodName(),
            DrcMethod.DRC_AI_SPOTLIGHT_ZOOM_TRACK.methodName(),
            DrcMethod.DRC_AI_SPOTLIGHT_ZOOM_SELECT.methodName(),
            DrcMethod.DRC_AI_SPOTLIGHT_ZOOM_CONFIRM.methodName(),
            DrcMethod.DRC_AI_SPOTLIGHT_ZOOM_STOP.methodName()
    );

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null);
    }

    private RuntimeConfig runtimeConfig(DockModel dockType) {
        RuntimeConfig rc = new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
        rc.setDockType(dockType);
        return rc;
    }

    /** 构造 DrcCommandHandler 并手动触发 init()（@PostConstruct 在单测中不自动执行） */
    private DrcCommandHandler newHandler(DockModel dockType) {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diagnosticRecorder = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder coverageRecorder = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = runtimeConfig(dockType);
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator aiSimulator = new AiSimulator(mqtt, rc, schema);
        DrcCommandHandler handler = new DrcCommandHandler(
                testProps(), mqtt, new ObjectMapper(), new DeviceState(),
                diagnosticRecorder, coverageRecorder, rc, schema, aiSimulator);
        handler.init();
        return handler;
    }

    /** 反射获取 handlers map（private 字段） */
    @SuppressWarnings("unchecked")
    private Map<String, ?> getHandlers(DrcCommandHandler handler) throws Exception {
        Field f = DrcCommandHandler.class.getDeclaredField("handlers");
        f.setAccessible(true);
        return (Map<String, ?>) f.get(handler);
    }

    @Test
    @DisplayName("TC-DRC-DOCK3-001：Dock3 注册全部 24 个 Dock3 专属 DRC 指令")
    void dock3_registersAllDock3SpecificMethods() throws Exception {
        DrcCommandHandler handler = newHandler(DockModel.DOCK3);
        Map<String, ?> handlers = getHandlers(handler);

        for (String method : DOCK3_METHODS) {
            assertTrue(handlers.containsKey(method),
                    () -> "Dock3 应注册 Dock3 专属指令: " + method);
        }
        assertEquals(DOCK3_METHODS.size(), DOCK3_METHODS.stream()
                .filter(handlers::containsKey).count(),
                "Dock3 应注册全部 24 个 Dock3 专属指令");
    }

    @Test
    @DisplayName("TC-DRC-DOCK3-002：Dock1 不注册任何 Dock3 专属指令，仍注册通用指令")
    void dock1_doesNotRegisterDock3SpecificMethods() throws Exception {
        DrcCommandHandler handler = newHandler(DockModel.DOCK1);
        Map<String, ?> handlers = getHandlers(handler);

        // Dock1 不注册任何 Dock3 专属指令
        for (String method : DOCK3_METHODS) {
            assertFalse(handlers.containsKey(method),
                    () -> "Dock1 不应注册 Dock3 专属指令: " + method);
        }

        // Dock1 仍注册三 Dock 共有的通用 DRC 指令
        assertTrue(handlers.containsKey(DrcMethod.STICK_CONTROL.methodName()),
                "Dock1 应注册通用飞行控制指令 stick_control");
        assertTrue(handlers.containsKey(DrcMethod.DRONE_CONTROL.methodName()),
                "Dock1 应注册通用飞行控制指令 drone_control");
    }

    @Test
    @DisplayName("TC-DRC-DOCK3-002：Dock2 不注册任何 Dock3 专属指令")
    void dock2_doesNotRegisterDock3SpecificMethods() throws Exception {
        DrcCommandHandler handler = newHandler(DockModel.DOCK2);
        Map<String, ?> handlers = getHandlers(handler);

        for (String method : DOCK3_METHODS) {
            assertFalse(handlers.containsKey(method),
                    () -> "Dock2 不应注册 Dock3 专属指令: " + method);
        }
        assertTrue(handlers.containsKey(DrcMethod.STICK_CONTROL.methodName()),
                "Dock2 应注册通用飞行控制指令 stick_control");
    }

    @Test
    @DisplayName("TC-DRC-DOCK3-003：Dock1 收到 Dock3 专属指令走兜底（result=0 + S-2 诊断日志）")
    void dock1_dock3MethodGoesFallback() throws Exception {
        // 重新构造以捕获 mqtt 回复（newHandler 内部 mock，这里取同一 mock）
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DiagnosticLogRecorder diagnosticRecorder = Mockito.mock(DiagnosticLogRecorder.class);
        CoverageRecorder coverageRecorder = Mockito.mock(CoverageRecorder.class);
        RuntimeConfig rc = runtimeConfig(DockModel.DOCK1);
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator aiSimulator = new AiSimulator(mqtt, rc, schema);
        DrcCommandHandler handler = new DrcCommandHandler(
                testProps(), mqtt, new ObjectMapper(), new DeviceState(),
                diagnosticRecorder, coverageRecorder, rc, schema, aiSimulator);
        handler.init();

        // 平台下发 Dock3 专属指令 drc_speaker_play_mode_set（Dock1 未注册）
        String payload = "{\"method\":\"drc_speaker_play_mode_set\",\"data\":{\"play_mode\":1},\"seq\":100}";
        Method m = DrcCommandHandler.class.getDeclaredMethod("handleDrcCommand", String.class, String.class);
        m.setAccessible(true);
        m.invoke(handler, "thing/product/sim-x/drc/down", payload);

        // 断言：兜底回复 result=0，method 与 seq 回显（publishJson 第二参数为 Object/Map）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> replyCaptor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), replyCaptor.capture());
        Map<String, Object> reply = replyCaptor.getValue();
        assertEquals("drc_speaker_play_mode_set", reply.get("method"),
                "兜底回复应回显 method: " + reply);
        @SuppressWarnings("unchecked")
        Map<String, Object> replyData = (Map<String, Object>) reply.get("data");
        assertEquals(0, replyData.get("result"),
                "兜底回复应为 result=0: " + reply);
        assertEquals(100, reply.get("seq"),
                "兜底回复应回显 seq=100: " + reply);

        // 断言：记录 S-2 诊断日志（未覆盖）
        Mockito.verify(diagnosticRecorder).record(
                Mockito.eq(DiagnosticCode.SIMULATOR_METHOD_NOT_IMPLEMENTED),
                Mockito.eq("drc_speaker_play_mode_set"),
                Mockito.anyString());

        assertNotNull(reply, "兜底回复不应为 null");
    }
}
