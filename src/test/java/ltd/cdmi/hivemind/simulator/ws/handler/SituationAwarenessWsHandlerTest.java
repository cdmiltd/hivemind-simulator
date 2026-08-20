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

package ltd.cdmi.hivemind.simulator.ws.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import ltd.cdmi.hivemind.simulator.http.api.DeviceTopoApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link SituationAwarenessWsHandler} 单元测试。
 * <p>对应 TDD-SPEC TC-TSA-001~006,009。
 */
class SituationAwarenessWsHandlerTest {

    private DeviceTopoApi deviceTopoApi;
    private SimulatorProperties props;
    private ObjectMapper objectMapper;
    private SituationAwarenessWsHandler handler;

    @BeforeEach
    void setUp() {
        deviceTopoApi = mock(DeviceTopoApi.class);
        props = new SimulatorProperties(null, new SimulatorProperties.Log(2000), null, null, null, null, null, null);
        objectMapper = new ObjectMapper();
        handler = new SituationAwarenessWsHandler(deviceTopoApi, props, objectMapper);
    }

    // ==================== TC-TSA-001：device_osd 仅记录事件日志，不触发 HTTP ====================

    @DisplayName("TC-TSA-001：device_osd 仅记录事件日志，不触发 HTTP")
    @Test
    void deviceOsdDoesNotTriggerHttpButRecordsEvent() throws Exception {
        String msg = "{\"biz_code\":\"device_osd\",\"version\":\"1.0\",\"timestamp\":146052438362,"
                + "\"data\":{\"sn\":\"drone01\",\"host\":{\"latitude\":113.44,\"longitude\":23.45,"
                + "\"height\":44,\"attitude_head\":90,\"elevation\":40,"
                + "\"horizontal_speed\":0,\"vertical_speed\":2.3}}}";
        JsonNode message = objectMapper.readTree(msg);

        handler.handle(message);

        // 不触发 HTTP 调用
        verifyNoInteractions(deviceTopoApi);
        // 记录事件日志
        List<Map<String, Object>> events = handler.getEvents();
        assertEquals(1, events.size());
        Map<String, Object> entry = events.get(0);
        assertEquals("device_osd", entry.get("biz_code"));
        assertEquals("drone01", entry.get("sn"));
        assertEquals(113.44, entry.get("latitude"));
        assertEquals(23.45, entry.get("longitude"));
        assertEquals(44.0, entry.get("height"));
        assertEquals(90.0, entry.get("attitude_head"));
        assertEquals(40.0, entry.get("elevation"));
        assertEquals(0.0, entry.get("horizontal_speed"));
        assertEquals(2.3, entry.get("vertical_speed"));
    }

    // ==================== TC-TSA-002：device_online 触发获取设备拓扑列表 ====================

    @DisplayName("TC-TSA-002：device_online 触发获取设备拓扑列表")
    @Test
    void deviceOnlineTriggersDeviceTopoFetch() throws Exception {
        when(deviceTopoApi.getDeviceTopo()).thenReturn(
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null));
        String msg = "{\"biz_code\":\"device_online\",\"version\":\"1.0\",\"timestamp\":146052438362,\"data\":{}}";
        JsonNode message = objectMapper.readTree(msg);

        handler.handle(message);

        verify(deviceTopoApi, times(1)).getDeviceTopo();
        assertEquals(1, handler.getEventCount());
        assertEquals("device_online", handler.getEvents().get(0).get("biz_code"));
    }

    // ==================== TC-TSA-003：device_offline 触发获取设备拓扑列表 ====================

    @DisplayName("TC-TSA-003：device_offline 触发获取设备拓扑列表")
    @Test
    void deviceOfflineTriggersDeviceTopoFetch() throws Exception {
        when(deviceTopoApi.getDeviceTopo()).thenReturn(
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null));
        String msg = "{\"biz_code\":\"device_offline\",\"version\":\"1.0\",\"timestamp\":146052438362,\"data\":{}}";
        JsonNode message = objectMapper.readTree(msg);

        handler.handle(message);

        verify(deviceTopoApi, times(1)).getDeviceTopo();
        assertEquals(1, handler.getEventCount());
        assertEquals("device_offline", handler.getEvents().get(0).get("biz_code"));
    }

    // ==================== TC-TSA-004：device_update_topo 触发获取设备拓扑列表 ====================

    @DisplayName("TC-TSA-004：device_update_topo 触发获取设备拓扑列表")
    @Test
    void deviceUpdateTopoTriggersDeviceTopoFetch() throws Exception {
        when(deviceTopoApi.getDeviceTopo()).thenReturn(
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null));
        String msg = "{\"biz_code\":\"device_update_topo\",\"version\":\"1.0\",\"timestamp\":146052438362,\"data\":{}}";
        JsonNode message = objectMapper.readTree(msg);

        handler.handle(message);

        verify(deviceTopoApi, times(1)).getDeviceTopo();
        assertEquals(1, handler.getEventCount());
        assertEquals("device_update_topo", handler.getEvents().get(0).get("biz_code"));
    }

    // ==================== TC-TSA-006：事件日志容量上限（FIFO） ====================

    @DisplayName("TC-TSA-006：态势感知事件日志容量上限")
    @Test
    void eventLogRespectsMaxSize() throws Exception {
        // 使用小容量上限便于测试
        props = new SimulatorProperties(null, new SimulatorProperties.Log(3), null, null, null, null, null, null);
        handler = new SituationAwarenessWsHandler(deviceTopoApi, props, objectMapper);
        when(deviceTopoApi.getDeviceTopo()).thenReturn(
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null));

        // 发送 5 条 device_online
        for (int i = 0; i < 5; i++) {
            String msg = "{\"biz_code\":\"device_online\",\"version\":\"1.0\",\"timestamp\":"
                    + i + ",\"data\":{}}";
            handler.handle(objectMapper.readTree(msg));
        }

        // 容量上限 3，只保留最新 3 条
        assertEquals(3, handler.getEventCount());
    }

    // ==================== supportedBizCodes 覆盖 4 种 ====================

    @DisplayName("补充测试：supportedBizCodes 覆盖 4 种 biz_code")
    @Test
    void supportedBizCodesCoversAllFour() {
        Set<String> codes = handler.supportedBizCodes();
        assertTrue(codes.contains("device_osd"));
        assertTrue(codes.contains("device_online"));
        assertTrue(codes.contains("device_offline"));
        assertTrue(codes.contains("device_update_topo"));
        assertEquals(4, codes.size());
    }

    // ==================== 清空事件日志 ====================

    @DisplayName("补充测试：清空事件日志")
    @Test
    void clearEventsEmptiesLog() throws Exception {
        when(deviceTopoApi.getDeviceTopo()).thenReturn(
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null));
        handler.handle(objectMapper.readTree(
                "{\"biz_code\":\"device_online\",\"version\":\"1.0\",\"timestamp\":1,\"data\":{}}"));
        assertEquals(1, handler.getEventCount());

        handler.clearEvents();
        assertEquals(0, handler.getEventCount());
        assertTrue(handler.getEvents().isEmpty());
    }
}
