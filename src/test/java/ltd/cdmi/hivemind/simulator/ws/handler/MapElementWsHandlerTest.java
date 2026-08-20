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
import ltd.cdmi.hivemind.simulator.http.api.MapElementApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MapElementWsHandler 单元测试。
 * <p>验证 TC-WS-003 ~ TC-WS-007：biz_code 分发、map_group_refresh 触发 HTTP 拉取、
 * 元素变更通知仅记录事件日志、事件日志容量上限、事件日志查询。
 * <p>核实依据：[DJI 地图元素 WebSocket 消息发布](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/websocket/map-elements/message-push.html)
 */
class MapElementWsHandlerTest {

    private MapElementApi mapElementApi;
    private SimulatorProperties props;
    private ObjectMapper objectMapper;
    private MapElementWsHandler handler;

    @BeforeEach
    void setUp() {
        mapElementApi = Mockito.mock(MapElementApi.class);
        // max-size=3 用于测试容量上限
        props = new SimulatorProperties(
                new SimulatorProperties.Location(30.6, 104.0, 500.0),
                new SimulatorProperties.Log(3),
                null, null, null, null, null, null
        );
        objectMapper = new ObjectMapper();
        handler = new MapElementWsHandler(mapElementApi, props, objectMapper);

        // mock fetchElements 返回成功响应
        when(mapElementApi.getElements(any()))
                .thenReturn(new HivemindHttpClient.HivemindResponse(true, 0, "ok", null));
    }

    // ==================== TC-WS-003：biz_code 分发 ====================

    @DisplayName("TC-WS-003：biz_code 分发支持全部地图元素 code")
    @Test
    void supportedBizCodesContainsAllMapElementCodes() {
        Set<String> codes = handler.supportedBizCodes();
        assertEquals(4, codes.size());
        assertTrue(codes.contains("map_element_create"));
        assertTrue(codes.contains("map_element_update"));
        assertTrue(codes.contains("map_element_delete"));
        assertTrue(codes.contains("map_group_refresh"));
    }

    // ==================== TC-WS-004：map_group_refresh 触发 HTTP 拉取 ====================

    @DisplayName("TC-WS-004：map_group_refresh 触发 HTTP 拉取")
    @Test
    void mapGroupRefreshTriggersFetchForEachGroupId() throws Exception {
        String payload = """
                {
                  "biz_code": "map_group_refresh",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {
                    "ids": ["group-a", "group-b"]
                  }
                }""";
        JsonNode message = objectMapper.readTree(payload);

        handler.handle(message);

        // 验证对每个 group_id 调用一次 fetchElements
        verify(mapElementApi).getElements("group-a");
        verify(mapElementApi).getElements("group-b");
        verify(mapElementApi, times(2)).getElements(any());
    }

    @DisplayName("TC-WS-004：map_group_refresh 空 ids 不触发拉取")
    @Test
    void mapGroupRefreshWithEmptyIdsDoesNotCallFetch() throws Exception {
        String payload = """
                {
                  "biz_code": "map_group_refresh",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {
                    "ids": []
                  }
                }""";
        JsonNode message = objectMapper.readTree(payload);

        handler.handle(message);

        verify(mapElementApi, never()).getElements(any());
    }

    @DisplayName("TC-WS-004：map_group_refresh 缺失 ids 不触发拉取")
    @Test
    void mapGroupRefreshWithMissingIdsArrayDoesNotCallFetch() throws Exception {
        String payload = """
                {
                  "biz_code": "map_group_refresh",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {}
                }""";
        JsonNode message = objectMapper.readTree(payload);

        handler.handle(message);

        verify(mapElementApi, never()).getElements(any());
    }

    // ==================== TC-WS-005：元素变更通知仅记录事件日志 ====================

    @DisplayName("TC-WS-005：map_element_create 仅记录事件日志")
    @Test
    void mapElementCreateDoesNotTriggerHttpButRecordsEvent() throws Exception {
        String payload = """
                {
                  "biz_code": "map_element_create",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {
                    "id": "elem-1",
                    "group_id": "group-a",
                    "name": "目标点",
                    "resource": {
                      "user_name": "user1",
                      "content": {"type": "Feature"},
                      "type": 0
                    }
                  }
                }""";
        JsonNode message = objectMapper.readTree(payload);

        handler.handle(message);

        // 不触发 HTTP 调用
        verify(mapElementApi, never()).getElements(any());
        // 记录事件日志
        List<Map<String, Object>> events = handler.getEvents();
        assertEquals(1, events.size());
        Map<String, Object> event = events.get(0);
        assertEquals("map_element_create", event.get("biz_code"));
        assertEquals("group-a", event.get("group_id"));
        assertEquals("elem-1", event.get("element_id"));
        assertEquals("目标点", event.get("name"));
        assertTrue(((String) event.get("payload")).contains("elem-1"));
    }

    @DisplayName("TC-WS-005：map_element_update 仅记录事件日志")
    @Test
    void mapElementUpdateRecordsEvent() throws Exception {
        String payload = """
                {
                  "biz_code": "map_element_update",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {
                    "id": "elem-2",
                    "group_id": "group-b",
                    "name": "更新后的点",
                    "resource": {"type": 0}
                  }
                }""";
        JsonNode message = objectMapper.readTree(payload);

        handler.handle(message);

        verify(mapElementApi, never()).getElements(any());
        List<Map<String, Object>> events = handler.getEvents();
        assertEquals(1, events.size());
        assertEquals("map_element_update", events.get(0).get("biz_code"));
        assertEquals("elem-2", events.get(0).get("element_id"));
    }

    @DisplayName("TC-WS-005：map_element_delete 仅记录事件日志")
    @Test
    void mapElementDeleteRecordsEvent() throws Exception {
        String payload = """
                {
                  "biz_code": "map_element_delete",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {
                    "id": "elem-3",
                    "group_id": "group-c"
                  }
                }""";
        JsonNode message = objectMapper.readTree(payload);

        handler.handle(message);

        verify(mapElementApi, never()).getElements(any());
        List<Map<String, Object>> events = handler.getEvents();
        assertEquals(1, events.size());
        assertEquals("map_element_delete", events.get(0).get("biz_code"));
        assertEquals("elem-3", events.get(0).get("element_id"));
        // delete 无 name 字段
        assertEquals("", events.get(0).get("name"));
    }

    @DisplayName("补充测试：map_group_refresh 也记录事件日志")
    @Test
    void mapGroupRefreshAlsoRecordsEvent() throws Exception {
        String payload = """
                {
                  "biz_code": "map_group_refresh",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {
                    "ids": ["group-a", "group-b"]
                  }
                }""";
        JsonNode message = objectMapper.readTree(payload);

        handler.handle(message);

        List<Map<String, Object>> events = handler.getEvents();
        assertEquals(1, events.size());
        assertEquals("map_group_refresh", events.get(0).get("biz_code"));
        // group_id 字段记录 ids 逗号拼接
        assertEquals("group-a,group-b", events.get(0).get("group_id"));
        assertEquals("", events.get(0).get("element_id"));
    }

    // ==================== TC-WS-006：事件日志容量上限 ====================

    @DisplayName("TC-WS-006：事件日志容量上限 FIFO")
    @Test
    void eventLogEvictsOldestWhenExceedingMaxSize() throws Exception {
        // max-size=3，发送 5 条消息，应只保留最新 3 条
        for (int i = 0; i < 5; i++) {
            String payload = String.format("""
                    {
                      "biz_code": "map_element_create",
                      "version": "1.0",
                      "timestamp": 146052438362,
                      "data": {
                        "id": "elem-%d",
                        "group_id": "group-a",
                        "name": "点%d"
                      }
                    }""", i, i);
            handler.handle(objectMapper.readTree(payload));
        }

        List<Map<String, Object>> events = handler.getEvents();
        assertEquals(3, events.size());
        // 应保留 elem-2, elem-3, elem-4（丢弃 elem-0, elem-1）
        assertEquals("elem-2", events.get(0).get("element_id"));
        assertEquals("elem-3", events.get(1).get("element_id"));
        assertEquals("elem-4", events.get(2).get("element_id"));
        assertEquals(3, handler.getEventCount());
    }

    // ==================== 事件日志查询与清空 ====================

    @DisplayName("补充测试：清空事件日志")
    @Test
    void clearEventsEmptiesLog() throws Exception {
        String payload = """
                {
                  "biz_code": "map_element_create",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {"id": "elem-1", "group_id": "group-a", "name": "点1"}
                }""";
        handler.handle(objectMapper.readTree(payload));

        assertEquals(1, handler.getEventCount());
        handler.clearEvents();
        assertEquals(0, handler.getEventCount());
        assertTrue(handler.getEvents().isEmpty());
    }

    @DisplayName("TC-WS-005：事件日志条目包含全部必需字段")
    @Test
    void eventLogEntryContainsAllRequiredFields() throws Exception {
        String payload = """
                {
                  "biz_code": "map_element_create",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {"id": "elem-1", "group_id": "group-a", "name": "测试点"}
                }""";
        handler.handle(objectMapper.readTree(payload));

        Map<String, Object> event = handler.getEvents().get(0);
        assertTrue(event.containsKey("time"));
        assertTrue(event.containsKey("biz_code"));
        assertTrue(event.containsKey("group_id"));
        assertTrue(event.containsKey("element_id"));
        assertTrue(event.containsKey("name"));
        assertTrue(event.containsKey("payload"));
    }
}
