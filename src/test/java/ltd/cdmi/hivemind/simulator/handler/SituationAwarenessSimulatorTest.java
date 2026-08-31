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

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import ltd.cdmi.hivemind.simulator.http.api.DeviceTopoApi;
import ltd.cdmi.hivemind.simulator.ws.handler.SituationAwarenessWsHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link SituationAwarenessSimulator} 单元测试。
 * <p>对应 TDD-SPEC TC-TSA-005,008。
 */
class SituationAwarenessSimulatorTest {

    private DeviceTopoApi deviceTopoApi;
    private RuntimeConfig runtimeConfig;
    private SituationAwarenessWsHandler wsHandler;
    private SituationAwarenessSimulator simulator;

    @BeforeEach
    void setUp() {
        deviceTopoApi = mock(DeviceTopoApi.class);
        runtimeConfig = mock(RuntimeConfig.class);
        wsHandler = mock(SituationAwarenessWsHandler.class);
        simulator = new SituationAwarenessSimulator(deviceTopoApi, runtimeConfig, wsHandler);
    }

    // ==================== TC-TSA-005：Pilot 首次上线主动调用获取设备拓扑列表 ====================

    @DisplayName("TC-TSA-005：Pilot 首次上线主动调用获取设备拓扑列表")
    @Test
    void initInPilotModeTriggersDeviceTopoFetch() {
        when(runtimeConfig.getDeviceMode()).thenReturn(DeviceMode.PILOT);
        when(deviceTopoApi.getDeviceTopo()).thenReturn(
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null));

        simulator.init();

        verify(deviceTopoApi, times(1)).getDeviceTopo();
    }

    // ==================== TC-TSA-008：非 Pilot 模式不触发首次获取设备拓扑 ====================

    @DisplayName("TC-TSA-008：非 Pilot 模式不触发首次获取设备拓扑")
    @Test
    void initInDockModeDoesNotTriggerFetch() {
        when(runtimeConfig.getDeviceMode()).thenReturn(DeviceMode.DOCK);

        simulator.init();

        verifyNoInteractions(deviceTopoApi);
    }

    // ==================== 事件日志委托给 wsHandler ====================

    @DisplayName("补充测试：getWsEvents 委托给 handler")
    @Test
    void getWsEventsDelegatesToHandler() {
        List<Map<String, Object>> expected = List.of(Map.of("biz_code", "device_online"));
        when(wsHandler.getEvents()).thenReturn(expected);

        List<Map<String, Object>> result = simulator.getWsEvents();

        assertSame(expected, result);
        verify(wsHandler, times(1)).getEvents();
    }

    @DisplayName("补充测试：getWsEventCount 委托给 handler")
    @Test
    void getWsEventCountDelegatesToHandler() {
        when(wsHandler.getEventCount()).thenReturn(42);

        assertEquals(42, simulator.getWsEventCount());
        verify(wsHandler, times(1)).getEventCount();
    }

    @DisplayName("补充测试：clearWsEvents 委托给 handler")
    @Test
    void clearWsEventsDelegatesToHandler() {
        simulator.clearWsEvents();

        verify(wsHandler, times(1)).clearEvents();
    }

    // ==================== fetchDeviceTopo 委托给 deviceTopoApi ====================

    @DisplayName("补充测试：fetchDeviceTopo 委托给 API")
    @Test
    void fetchDeviceTopoDelegatesToApi() {
        HivemindHttpClient.HivemindResponse expected =
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null);
        when(deviceTopoApi.getDeviceTopo()).thenReturn(expected);

        HivemindHttpClient.HivemindResponse result = simulator.fetchDeviceTopo();

        assertSame(expected, result);
        verify(deviceTopoApi, times(1)).getDeviceTopo();
    }
}
