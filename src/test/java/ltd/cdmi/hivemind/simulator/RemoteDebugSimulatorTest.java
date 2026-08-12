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
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
import ltd.cdmi.hivemind.simulator.handler.RemoteDebugSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RemoteDebugSimulator 单元测试。
 * <p>覆盖 Dock1 独有指令（putter_open / putter_close）和 Dock3 独有指令（rtk_calibration），
 * 验证 Job 指令的双阶段确认（services_reply + events 进度）、状态同步、三 Dock 差异校验。
 * <p>对应 TDD-SPEC.md TC-RD-002~015。
 */
class RemoteDebugSimulatorTest {

    private static final long AWAIT_MS = 3000;

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", ""),
                new SimulatorProperties.Media("")
        );
    }

    private MqttProperties testMqttProps() {
        return new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-");
    }

    private RemoteDebugSimulator createSimulator(DeviceType dockType, DeviceState state, MqttClientManager mqtt) {
        RuntimeConfig runtimeConfig = new RuntimeConfig(testMqttProps(), testProps(), new LiveConfigStore());
        runtimeConfig.setDockType(dockType);
        return new RemoteDebugSimulator(mqtt, state, runtimeConfig);
    }

    // ==================== Dock1 putter_open ====================

    /**
     * TC-RD-007：Dock1 putter_open → services_reply result=0 + 进度事件 in_progress→ok + putterExpanded=true
     */
    @SuppressWarnings("unchecked")
    @Test
    void dock1_putterOpen_returnsResult0_schedulesProgressEvents_syncsState() throws Exception {
        DeviceState state = new DeviceState();
        state.setPutterExpanded(false);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RemoteDebugSimulator simulator = createSimulator(DeviceType.DOCK1, state, mqtt);

        Map<String, Object> result = simulator.handle("putter_open", null, "bid-putter-open");

        // services_reply 返回 result=0
        assertEquals(0, result.get("result"));

        // 等待 2 次进度事件（in_progress + ok）
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.timeout(AWAIT_MS).times(2))
                .publishJson(Mockito.anyString(), captor.capture());

        // 验证事件结构
        ObjectMapper objectMapper = new ObjectMapper();
        var events = captor.getAllValues();
        assertEquals(2, events.size());

        // 第 1 次：in_progress, percent=50
        JsonNode event1 = objectMapper.readTree(objectMapper.writeValueAsString(events.get(0)));
        assertEquals("putter_open", event1.path("method").asText());
        assertEquals("bid-putter-open", event1.path("bid").asText());
        assertEquals(0, event1.path("data").path("result").asInt());
        assertEquals("in_progress", event1.path("data").path("output").path("status").asText());
        assertEquals(50, event1.path("data").path("output").path("progress").path("percent").asInt());

        // 第 2 次：ok, percent=100
        JsonNode event2 = objectMapper.readTree(objectMapper.writeValueAsString(events.get(1)));
        assertEquals("putter_open", event2.path("method").asText());
        assertEquals("bid-putter-open", event2.path("bid").asText());
        assertEquals("ok", event2.path("data").path("output").path("status").asText());
        assertEquals(100, event2.path("data").path("output").path("progress").path("percent").asInt());

        // 状态同步：putterExpanded → true（TC-RD-007）
        Thread.sleep(100); // 等待 syncDeviceState 在同一线程执行完毕
        assertTrue(state.isPutterExpanded(), "putter_open 完成后 putterExpanded 应为 true");
    }

    // ==================== Dock1 putter_close ====================

    /**
     * TC-RD-007：Dock1 putter_close → services_reply result=0 + 进度事件 + putterExpanded=false
     */
    @Test
    void dock1_putterClose_returnsResult0_schedulesProgressEvents_syncsState() throws Exception {
        DeviceState state = new DeviceState();
        state.setPutterExpanded(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RemoteDebugSimulator simulator = createSimulator(DeviceType.DOCK1, state, mqtt);

        Map<String, Object> result = simulator.handle("putter_close", null, "bid-putter-close");

        assertEquals(0, result.get("result"));

        // 等待 2 次进度事件
        Mockito.verify(mqtt, Mockito.timeout(AWAIT_MS).times(2))
                .publishJson(Mockito.anyString(), Mockito.any());

        // 状态同步：putterExpanded → false
        Thread.sleep(100);
        assertFalse(state.isPutterExpanded(), "putter_close 完成后 putterExpanded 应为 false");
    }

    // ==================== Dock3 rtk_calibration ====================

    /**
     * TC-RD-008/012：Dock3 rtk_calibration → services_reply result=0 + 进度事件，无状态变更
     */
    @Test
    void dock3_rtkCalibration_returnsResult0_schedulesProgressEvents_noStateChange() throws Exception {
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RemoteDebugSimulator simulator = createSimulator(DeviceType.DOCK3, state, mqtt);

        Map<String, Object> result = simulator.handle("rtk_calibration", null, "bid-rtk");

        assertEquals(0, result.get("result"));

        // 等待 2 次进度事件
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.timeout(AWAIT_MS).times(2))
                .publishJson(Mockito.anyString(), captor.capture());

        // 验证事件 method 和 bid
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode event1 = objectMapper.readTree(objectMapper.writeValueAsString(captor.getAllValues().get(0)));
        assertEquals("rtk_calibration", event1.path("method").asText());
        assertEquals("bid-rtk", event1.path("bid").asText());
        assertEquals("in_progress", event1.path("data").path("output").path("status").asText());

        JsonNode event2 = objectMapper.readTree(objectMapper.writeValueAsString(captor.getAllValues().get(1)));
        assertEquals("ok", event2.path("data").path("output").path("status").asText());

        // 无状态变更（TC-RD-008）
        Thread.sleep(100);
        // rtk_calibration 不修改任何 DeviceState 字段，验证 coverOpen 等保持默认值
        assertFalse(state.isCoverOpen(), "rtk_calibration 不应修改 coverOpen");
        assertFalse(state.isPutterExpanded(), "rtk_calibration 不应修改 putterExpanded");
    }

    // ==================== Dock2 putter_open（不支持） ====================

    /**
     * TC-RD-010：Dock2 收到 putter_open → 占位 result=0，无进度事件，无状态变更
     */
    @Test
    void dock2_putterOpen_unsupported_returnsPlaceholder_noEvents_noStateChange() {
        DeviceState state = new DeviceState();
        state.setPutterExpanded(false);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RemoteDebugSimulator simulator = createSimulator(DeviceType.DOCK2, state, mqtt);

        Map<String, Object> result = simulator.handle("putter_open", null, "bid-unsupported");

        assertEquals(0, result.get("result"));
        Mockito.verifyNoInteractions(mqtt); // 无进度事件
        assertFalse(state.isPutterExpanded(), "不支持的指令不应修改状态");
    }

    // ==================== Dock1 rtk_calibration（不支持） ====================

    /**
     * TC-RD-012：Dock1 收到 rtk_calibration → 占位 result=0，无进度事件，无状态变更
     */
    @Test
    void dock1_rtkCalibration_unsupported_returnsPlaceholder_noEvents_noStateChange() {
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RemoteDebugSimulator simulator = createSimulator(DeviceType.DOCK1, state, mqtt);

        Map<String, Object> result = simulator.handle("rtk_calibration", null, "bid-unsupported");

        assertEquals(0, result.get("result"));
        Mockito.verifyNoInteractions(mqtt); // 无进度事件
    }

    // ==================== isRemoteDebugMethod 静态方法 ====================

    /**
     * TC-RD-001/009：isRemoteDebugMethod 正确识别远程调试指令
     */
    @Test
    void isRemoteDebugMethod_recognizesRemoteDebugCommands() {
        // Job 指令
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("cover_open"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("cover_close"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("drone_open"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("device_reboot"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("putter_open"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("putter_close"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("esim_activate"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("rtk_calibration"));

        // Cmd 指令
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("debug_mode_open"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("debug_mode_close"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("supplement_light_open"));
        assertTrue(RemoteDebugSimulator.isRemoteDebugMethod("sim_slot_switch"));

        // 非远程调试指令
        assertFalse(RemoteDebugSimulator.isRemoteDebugMethod("fly_to_point"));
        assertFalse(RemoteDebugSimulator.isRemoteDebugMethod("takeoff_to_point"));
        assertFalse(RemoteDebugSimulator.isRemoteDebugMethod("live_start_push"));
        assertFalse(RemoteDebugSimulator.isRemoteDebugMethod("flighttask_execute"));
        assertFalse(RemoteDebugSimulator.isRemoteDebugMethod("unknown_method"));
    }
}
