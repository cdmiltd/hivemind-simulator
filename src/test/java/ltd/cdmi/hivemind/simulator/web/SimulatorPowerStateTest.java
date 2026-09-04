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

package ltd.cdmi.hivemind.simulator.web;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.device.PilotOnlineService;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.media.FfmpegInstaller;
import ltd.cdmi.hivemind.simulator.media.FfmpegWhipPusher;
import ltd.cdmi.hivemind.simulator.media.LocalFileUploadService;
import ltd.cdmi.hivemind.simulator.media.MediaSampleInitializer;
import ltd.cdmi.hivemind.simulator.handler.AirSenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.EsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator;
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.handler.HmsSimulator;
import ltd.cdmi.hivemind.simulator.handler.LiveStreamSimulator;
import ltd.cdmi.hivemind.simulator.handler.MapElementSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.OtaSimulator;
import ltd.cdmi.hivemind.simulator.handler.PilotHttpSimulator;
import ltd.cdmi.hivemind.simulator.handler.PsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.RemoteLogSimulator;
import ltd.cdmi.hivemind.simulator.handler.SituationAwarenessSimulator;
import ltd.cdmi.hivemind.simulator.handler.UnlockLicenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.ws.MopClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 电源状态（powered）单元测试，对应 TDD-SPEC TC-MQTT-008/009。
 * <p>验证开机状态由后端管理：power/on 置位、offline 复位、disconnect 不复位、
 * connection 返回 powered 供前端刷新后恢复。</p>
 * <p>核实依据：真实机场通电即开机（连不上云平台也是开机状态），电源状态属于设备本体；
 * 刷新页面只重建前端 UI，不应改变设备电源状态。</p>
 */
class SimulatorPowerStateTest {

    private DeviceState state;
    private SimulatorController controller;

    @BeforeEach
    void setUp() {
        state = new DeviceState();
        controller = newController(state, mock(MqttClientManager.class));
    }

    /** 构造 Controller：除 DeviceState（被测状态载体）与 MqttClientManager（被测连接管理）外全部 mock */
    private SimulatorController newController(DeviceState state, MqttClientManager mqtt) {
        RuntimeConfig runtimeConfig = mock(RuntimeConfig.class);
        when(runtimeConfig.getDeviceMode()).thenReturn(DeviceMode.DOCK);
        return new SimulatorController(
                mock(DockOnlineService.class), mock(PilotOnlineService.class),
                state, mqtt, mock(WaylineTaskSimulator.class),
                mock(LiveStreamSimulator.class), mock(MediaUploadSimulator.class),
                mock(HmsSimulator.class), mock(AirSenseSimulator.class),
                mock(FlightAreaSimulator.class), mock(UnlockLicenseSimulator.class),
                mock(PsdkSimulator.class), mock(EsdkSimulator.class),
                mock(RemoteLogSimulator.class), mock(OtaSimulator.class),
                mock(FlightCommandSimulator.class),
                mock(FfmpegWhipPusher.class), mock(FfmpegInstaller.class),
                mock(LocalFileUploadService.class), mock(MediaSampleInitializer.class),
                runtimeConfig, mock(SimulatorProperties.class),
                mock(DiagnosticLogRecorder.class), mock(CoverageRecorder.class),
                mock(ObjectMapper.class), mock(MapElementSimulator.class),
                mock(SituationAwarenessSimulator.class), mock(PilotHttpSimulator.class),
                mock(MopClient.class), mock(ltd.cdmi.hivemind.simulator.mqtt.DrcConnectionManager.class));
    }

    @Test
    @DisplayName("后端启动默认关机：connection 返回 powered=false")
    void connection_defaultsToPoweredOff() {
        Map<String, Object> result = controller.getConnection();
        assertEquals(false, result.get("powered"), "进程启动视为断电，powered 应为 false");
    }

    @Test
    @DisplayName("开机后未连接 MQTT 也保持 powered=true（刷新页面恢复依据，TC-MQTT-008）")
    void powerOn_keepsPoweredEvenWithoutMqtt() {
        controller.powerOn();
        Map<String, Object> result = controller.getConnection();
        assertEquals(true, result.get("powered"), "开机≠已连接 MQTT，未注册/重连失败时 powered 也应为 true");
        assertEquals(false, result.get("mqtt_connected"), "未建立 MQTT 连接");
        assertEquals(false, result.get("online"), "未上线");
    }

    @Test
    @DisplayName("关机（offline）复位 powered=false（TC-MQTT-009）")
    void offline_resetsPowered() {
        controller.powerOn();
        controller.offline();
        Map<String, Object> result = controller.getConnection();
        assertEquals(false, result.get("powered"), "关机 = 电源关闭，powered 应回到 false");
    }

    @Test
    @DisplayName("断开 MQTT（disconnect）不改变电源状态（TC-MQTT-009）")
    void disconnect_keepsPowered() {
        controller.powerOn();
        controller.disconnect();
        Map<String, Object> result = controller.getConnection();
        assertEquals(true, result.get("powered"), "注册失败断开 MQTT ≠ 关机，设备仍通电");
    }

    @Test
    @DisplayName("已连接且在线时 /api/connect 幂等返回，不重连不重置 online（TC-MQTT-014）")
    void connect_whenAlreadyConnectedAndOnline_skipsReconnect() {
        MqttClientManager mqtt = mock(MqttClientManager.class);
        when(mqtt.isConnected()).thenReturn(true);
        SimulatorController ctrl = newController(state, mqtt);
        state.setOnline(true);

        Map<String, Object> result = ctrl.connect(java.util.Map.of());

        assertEquals(true, result.get("success"), "应返回成功");
        assertEquals(true, result.get("online"), "online 应保持 true");
        verify(mqtt, never()).reconnect();
        assertTrue(state.isOnline(), "online 状态未被重置");
    }

    @Test
    @DisplayName("MQTT 真断开时 /api/connect 重置 online + 重连（TC-MQTT-014 边界）")
    void connect_whenMqttDisconnected_reconnectsAndResetsOnline() {
        MqttClientManager mqtt = mock(MqttClientManager.class);
        when(mqtt.isConnected()).thenReturn(false);
        when(mqtt.reconnect()).thenReturn(null);
        SimulatorController ctrl = newController(state, mqtt);
        state.setOnline(true);

        Map<String, Object> result = ctrl.connect(java.util.Map.of());

        verify(mqtt, times(1)).reconnect();
        assertFalse(state.isOnline(), "online 被重置（重连后需重新上线）");
    }
}
