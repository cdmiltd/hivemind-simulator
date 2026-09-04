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
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * DRC 拍照相关推送单元测试。
 * <p>覆盖 TDD-SPEC：
 * <ul>
 *   <li>TC-DRC-058：drc_camera_state_push 包含 photo_format 字段</li>
 *   <li>TC-DRC-059：drc_camera_osd_info_push 红外镜头包含 thermal_supported_palette_styles</li>
 *   <li>TC-DRC-060：drc_camera_photo_take 触发 drc_camera_photo_info_push 推送</li>
 * </ul>
 * <p>核实依据：DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html">Dock3 远程控制</a>。
 */
class DrcPhotoInfoPushTest {

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

    /** 反射调用 DrcCommandHandler.handleDrcCommand(topic, payload) */
    private void invokeHandleDrcCommand(DrcCommandHandler handler, String payload) throws Exception {
        Method m = DrcCommandHandler.class.getDeclaredMethod("handleDrcCommand", String.class, String.class);
        m.setAccessible(true);
        m.invoke(handler, DRC_DOWN_TOPIC, payload);
    }

    /** 捕获全部 publishJson 调用的 Map 参数 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> captureAllPublishes(MqttClientManager mqtt) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mqtt, atLeastOnce()).publishJson(anyString(), captor.capture());
        return captor.getAllValues();
    }

    private DrcCommandHandler newHandler(MqttClientManager mqtt, DeviceState state) {
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        AiSimulator aiSimulator = new AiSimulator(mqtt, rc, schema);
        DrcCommandHandler handler = new DrcCommandHandler(
                testProps(), mqtt, new ObjectMapper(), state,
                Mockito.mock(DiagnosticLogRecorder.class), Mockito.mock(CoverageRecorder.class),
                rc, schema, aiSimulator);
        handler.init();
        return handler;
    }

    // ==================== TC-DRC-060：drc_camera_photo_take 触发 photo_info_push ====================

    @DisplayName("TC-DRC-060：drc_camera_photo_take 立即推送 in_progress，完成后推送 ok 并归零 photoState")
    @Test
    void photoTakePushesInProgressThenOk() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = new DeviceState();
        DrcCommandHandler handler = newHandler(mqtt, state);

        invokeHandleDrcCommand(handler,
                "{\"method\":\"drc_camera_photo_take\",\"data\":{\"payload_index\":\"81-0-0\"},\"seq\":60}");

        // 拍照进行中：photoState=1
        assertEquals(1, state.getPhotoState(), "拍照指令后 photoState 应为 1");

        // 等待拍照完成模拟（2 秒延迟 + 缓冲）
        Thread.sleep(2500);

        // 完成后归零
        assertEquals(0, state.getPhotoState(), "拍照完成后 photoState 应归 0");

        // 验证推送序列：in_progress（percent=0）→ reply → ok（percent=100）
        List<Map<String, Object>> publishes = captureAllPublishes(mqtt);
        List<Map<String, Object>> photoInfos = publishes.stream()
                .filter(p -> "drc_camera_photo_info_push".equals(p.get("method")))
                .toList();
        assertEquals(2, photoInfos.size(), "应推送 2 条 photo_info_push（in_progress + ok）");

        Map<String, Object> inProgress = photoInfos.get(0);
        Map<String, Object> inProgressData = (Map<String, Object>) inProgress.get("data");
        assertEquals(0, inProgressData.get("result"));
        assertEquals("in_progress", inProgressData.get("status"));
        Map<String, Object> inProgressProgress = (Map<String, Object>) inProgressData.get("progress");
        assertEquals(0, inProgressProgress.get("percent"), "in_progress 进度应为 0%");
        assertTrue(inProgress.containsKey("timestamp"), "事件推送应含 timestamp");
        assertTrue(inProgress.containsKey("seq"), "事件推送应含 seq");

        Map<String, Object> ok = photoInfos.get(1);
        Map<String, Object> okData = (Map<String, Object>) ok.get("data");
        assertEquals(0, okData.get("result"));
        assertEquals("ok", okData.get("status"));
        Map<String, Object> okProgress = (Map<String, Object>) okData.get("progress");
        assertEquals(100, okProgress.get("percent"), "ok 进度应为 100%");
    }

    @DisplayName("TC-DRC-060：drc_camera_photo_stop 中断拍照后不推送 ok")
    @Test
    void photoStopInterruptsPhotoTake() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = new DeviceState();
        DrcCommandHandler handler = newHandler(mqtt, state);

        // 拍照开始后立即停止（模拟中断）
        invokeHandleDrcCommand(handler,
                "{\"method\":\"drc_camera_photo_take\",\"data\":{\"payload_index\":\"81-0-0\"},\"seq\":61}");
        invokeHandleDrcCommand(handler,
                "{\"method\":\"drc_camera_photo_stop\",\"data\":{\"payload_index\":\"81-0-0\"},\"seq\":62}");

        assertEquals(0, state.getPhotoState(), "停止拍照后 photoState 应为 0");

        // 等待超过拍照完成延迟，验证没有 ok 推送
        Thread.sleep(2500);

        List<Map<String, Object>> publishes = captureAllPublishes(mqtt);
        List<Map<String, Object>> photoInfos = publishes.stream()
                .filter(p -> "drc_camera_photo_info_push".equals(p.get("method")))
                .toList();
        assertEquals(1, photoInfos.size(), "中断后应只有 1 条 in_progress，无 ok 推送");
        Map<String, Object> data = (Map<String, Object>) photoInfos.get(0).get("data");
        assertEquals("in_progress", data.get("status"), "唯一一条应为 in_progress");
    }
}
