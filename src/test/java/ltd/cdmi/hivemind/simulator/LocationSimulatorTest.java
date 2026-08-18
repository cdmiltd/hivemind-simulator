// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package ltd.cdmi.hivemind.simulator;

import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.device.PilotOnlineService;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.media.FfmpegInstaller;
import ltd.cdmi.hivemind.simulator.media.FfmpegWhipPusher;
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.handler.AirSenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator;
import ltd.cdmi.hivemind.simulator.handler.PsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.EsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.RemoteLogSimulator;
import ltd.cdmi.hivemind.simulator.handler.OtaSimulator;
import ltd.cdmi.hivemind.simulator.handler.UnlockLicenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.HmsSimulator;
import ltd.cdmi.hivemind.simulator.handler.LiveStreamSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.handler.MapElementSimulator;
import ltd.cdmi.hivemind.simulator.handler.SituationAwarenessSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.web.SimulatorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 机场位置与无人机位置相关测试。
 * <p>覆盖 TC-LOC-001/002/003/007/009：配置链路、REST API、参数校验、无人机位置 API、持久化向后兼容。</p>
 */
class LocationSimulatorTest {

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null,
                null
        );
    }

    private MqttProperties testMqttProps() {
        return new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-");
    }

    /** 创建 SimulatorController，仅初始化必要的依赖 */
    private SimulatorController newController(RuntimeConfig runtimeConfig, DeviceState state) {
        return new SimulatorController(
                Mockito.mock(DockOnlineService.class),
                Mockito.mock(PilotOnlineService.class),
                state,
                Mockito.mock(MqttClientManager.class),
                Mockito.mock(WaylineTaskSimulator.class),
                Mockito.mock(LiveStreamSimulator.class),
                Mockito.mock(MediaUploadSimulator.class),
                Mockito.mock(HmsSimulator.class),
                Mockito.mock(AirSenseSimulator.class),
                Mockito.mock(FlightAreaSimulator.class),
                Mockito.mock(UnlockLicenseSimulator.class),
                Mockito.mock(PsdkSimulator.class),
                Mockito.mock(EsdkSimulator.class),
                Mockito.mock(RemoteLogSimulator.class),
                Mockito.mock(OtaSimulator.class),
                Mockito.mock(FlightCommandSimulator.class),
                Mockito.mock(FfmpegWhipPusher.class),
                Mockito.mock(FfmpegInstaller.class),
                runtimeConfig,
                testProps(),
                Mockito.mock(DiagnosticLogRecorder.class),
                Mockito.mock(CoverageRecorder.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Mockito.mock(MapElementSimulator.class),
                Mockito.mock(SituationAwarenessSimulator.class),
                Mockito.mock(ltd.cdmi.hivemind.simulator.handler.PilotHttpSimulator.class),
                Mockito.mock(ltd.cdmi.hivemind.simulator.ws.MopClient.class)
        );
    }

    // ==================== TC-LOC-001：机场位置配置链路 ====================

    /**
     * yml → SimulatorProperties → RuntimeConfig，无持久化文件时使用 yml 默认值。
     */
    @DisplayName("TC-LOC-001：机场位置配置链路（yml 默认值）")
    @Test
    void configChainLoadsFromYmlDefaults() {
        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        Mockito.when(store.load()).thenReturn(null); // 文件不存在

        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);

        assertEquals(30.67, rc.getLocationLatitude());
        assertEquals(104.07, rc.getLocationLongitude());
        assertEquals(500.0, rc.getLocationHeight());
    }

    /**
     * LiveConfigStore 持久化文件存在时覆盖 yml 默认值。
     */
    @DisplayName("TC-LOC-001：机场位置配置链路（LiveConfigStore 覆盖 yml）")
    @Test
    void configChainLiveConfigStoreOverridesYml() {
        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        Mockito.when(store.load()).thenReturn(new LiveConfigStore.LiveConfig(
                false, "ffmpeg", "/videos", "/media",
                31.23, 121.47, 10.0));

        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);

        assertEquals(31.23, rc.getLocationLatitude());
        assertEquals(121.47, rc.getLocationLongitude());
        assertEquals(10.0, rc.getLocationHeight());
    }

    // ==================== TC-LOC-002：机场位置 REST API ====================

    /**
     * GET /api/location 返回当前机场位置。
     */
    @DisplayName("TC-LOC-002：机场位置 REST API（GET /api/location）")
    @Test
    void getLocationReturnsCurrentPosition() {
        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        Mockito.when(store.load()).thenReturn(null);
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);
        SimulatorController controller = newController(rc, new DeviceState());

        Map<String, Object> result = controller.getLocation();

        assertEquals(30.67, result.get("latitude"));
        assertEquals(104.07, result.get("longitude"));
        assertEquals(500.0, result.get("height"));
    }

    /**
     * PUT /api/location 更新位置并持久化，返回 success=true + 新坐标。
     */
    @DisplayName("TC-LOC-002：机场位置 REST API（PUT /api/location）")
    @Test
    void updateLocationSavesAndPersists() {
        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        Mockito.when(store.load()).thenReturn(null);
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);
        SimulatorController controller = newController(rc, new DeviceState());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 31.23);
        body.put("longitude", 121.47);
        body.put("height", 10.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = controller.updateLocation(body);

        assertEquals(true, result.get("success"));
        assertEquals(31.23, result.get("latitude"));
        assertEquals(121.47, result.get("longitude"));
        assertEquals(10.0, result.get("height"));
        // 验证 RuntimeConfig 已更新
        assertEquals(31.23, rc.getLocationLatitude());
        assertEquals(121.47, rc.getLocationLongitude());
        assertEquals(10.0, rc.getLocationHeight());
        // 验证已调用持久化
        Mockito.verify(store).save(false, "", "", "", 31.23, 121.47, 10.0);
    }

    // ==================== TC-LOC-003：机场位置参数校验 ====================

    /**
     * 纬度/经度/高度非数字时返回 success=false + 错误消息，HTTP 仍为 200。
     */
    @DisplayName("TC-LOC-003：机场位置参数校验（非数字）")
    @Test
    void updateLocationRejectsNonNumeric() {
        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        Mockito.when(store.load()).thenReturn(null);
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);
        SimulatorController controller = newController(rc, new DeviceState());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", "abc");
        body.put("longitude", 121.47);
        body.put("height", 10.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = controller.updateLocation(body);

        assertEquals(false, result.get("success"));
        assertEquals("纬度/经度/高度必须为数字", result.get("message"));
    }

    /**
     * 纬度超出 -90~90 范围时返回 success=false。
     */
    @DisplayName("TC-LOC-003：机场位置参数校验（纬度超范围）")
    @Test
    void updateLocationRejectsLatitudeOutOfRange() {
        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        Mockito.when(store.load()).thenReturn(null);
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);
        SimulatorController controller = newController(rc, new DeviceState());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 91.0);
        body.put("longitude", 121.47);
        body.put("height", 10.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = controller.updateLocation(body);

        assertEquals(false, result.get("success"));
        assertEquals("纬度范围应为 -90 ~ 90", result.get("message"));
    }

    /**
     * 经度超出 -180~180 范围时返回 success=false。
     */
    @DisplayName("TC-LOC-003：机场位置参数校验（经度超范围）")
    @Test
    void updateLocationRejectsLongitudeOutOfRange() {
        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        Mockito.when(store.load()).thenReturn(null);
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);
        SimulatorController controller = newController(rc, new DeviceState());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 31.23);
        body.put("longitude", 181.0);
        body.put("height", 10.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = controller.updateLocation(body);

        assertEquals(false, result.get("success"));
        assertEquals("经度范围应为 -180 ~ 180", result.get("message"));
    }

    // ==================== TC-LOC-007：无人机位置 REST API ====================

    /**
     * GET /api/drone/position 返回 {latitude, longitude, height, mode_code, in_dock, activated}。
     */
    @DisplayName("TC-LOC-007：无人机位置 REST API（已激活）")
    @Test
    void getDronePositionReturnsAllFields() {
        DeviceState state = new DeviceState();
        state.setDroneActivated(true);
        state.setDroneInDock(false);
        state.setDroneModeCode(5);
        state.setDroneLatitude(31.23);
        state.setDroneLongitude(121.47);
        state.setDroneHeight(50.0);

        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        Mockito.when(store.load()).thenReturn(null);
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);
        SimulatorController controller = newController(rc, state);

        Map<String, Object> result = controller.getDronePosition();

        assertEquals(31.23, result.get("latitude"));
        assertEquals(121.47, result.get("longitude"));
        assertEquals(50.0, result.get("height"));
        assertEquals(5, result.get("mode_code"));
        assertEquals(false, result.get("in_dock"));
        assertEquals(true, result.get("activated"));
    }

    /**
     * 飞行器未激活时位置字段为默认值（前端应据此显示"-"）。
     */
    @DisplayName("TC-LOC-007：无人机位置 REST API（未激活）")
    @Test
    void getDronePositionWhenNotActivated() {
        DeviceState state = new DeviceState();
        // 默认 droneActivated=false, droneInDock=true

        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        Mockito.when(store.load()).thenReturn(null);
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);
        SimulatorController controller = newController(rc, state);

        Map<String, Object> result = controller.getDronePosition();

        assertEquals(false, result.get("activated"));
        assertEquals(true, result.get("in_dock"));
        assertEquals(0.0, result.get("latitude"));
        assertEquals(0.0, result.get("longitude"));
        assertEquals(0.0, result.get("height"));
    }

    // ==================== TC-LOC-009：机场位置持久化向后兼容 ====================

    /**
     * 旧配置文件缺少 location 字段时 Jackson 填充 0.0，
     * RuntimeConfig 检测到三字段同时为 0.0 时回退到 yml 默认值。
     */
    @DisplayName("TC-LOC-009：机场位置持久化向后兼容（三字段为 0.0 回退 yml）")
    @Test
    void backwardCompatFallsBackToYmlWhenLocationZero() {
        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        // 模拟旧配置文件：location 字段缺失，Jackson 填充 0.0
        Mockito.when(store.load()).thenReturn(new LiveConfigStore.LiveConfig(
                false, "ffmpeg", "/videos", "/media",
                0.0, 0.0, 0.0));

        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);

        // 应回退到 yml 默认值（30.67, 104.07, 500.0），而非 0.0
        assertEquals(30.67, rc.getLocationLatitude());
        assertEquals(104.07, rc.getLocationLongitude());
        assertEquals(500.0, rc.getLocationHeight());
    }

    /**
     * 仅部分 location 字段为 0.0 时不视为未配置（如赤道地区纬度=0）。
     */
    @DisplayName("TC-LOC-009：机场位置持久化向后兼容（部分为 0.0 不回退）")
    @Test
    void backwardCompatKeepsPartialZeroLocation() {
        LiveConfigStore store = Mockito.mock(LiveConfigStore.class);
        // 纬度=0（赤道），经度和高度非零
        Mockito.when(store.load()).thenReturn(new LiveConfigStore.LiveConfig(
                false, "ffmpeg", "/videos", "/media",
                0.0, 121.47, 10.0));

        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), testProps(), store);

        // 三字段不全部为 0.0，应使用持久化值
        assertEquals(0.0, rc.getLocationLatitude());
        assertEquals(121.47, rc.getLocationLongitude());
        assertEquals(10.0, rc.getLocationHeight());
    }
}
