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

package ltd.cdmi.hivemind.simulator.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * HivemindWsClient 单元测试。
 * <p>验证 TC-WS-002（未配置 url 时 connect 跳过）和 TC-WS-003（biz_code 分发逻辑）。
 * <p>connect() 涉及真实 WebSocket 连接，TC-WS-001 的连接成功部分属集成测试范畴，此处不覆盖。
 */
class HivemindWsClientTest {

    private RuntimeConfig runtimeConfig;
    private ObjectMapper objectMapper;
    private WsMessageHandler mapElementHandler;

    @BeforeEach
    void setUp() {
        runtimeConfig = Mockito.mock(RuntimeConfig.class);
        objectMapper = new ObjectMapper();
        mapElementHandler = Mockito.mock(WsMessageHandler.class);
        when(mapElementHandler.supportedBizCodes()).thenReturn(Set.of(
                "map_element_create", "map_element_update", "map_element_delete", "map_group_refresh"
        ));
    }

    // ==================== TC-WS-002：未配置 url 时 connect 跳过 ====================

    @Test
    void connectSkipsWhenWsUrlNotConfigured() {
        when(runtimeConfig.getHivemindWsUrl()).thenReturn("");
        HivemindWsClient client = new HivemindWsClient(runtimeConfig, objectMapper, List.of(mapElementHandler));

        client.connect();

        assertFalse(client.isConnected());
    }

    @Test
    void connectSkipsWhenWsUrlIsNull() {
        when(runtimeConfig.getHivemindWsUrl()).thenReturn(null);
        HivemindWsClient client = new HivemindWsClient(runtimeConfig, objectMapper, List.of(mapElementHandler));

        client.connect();

        assertFalse(client.isConnected());
    }

    // ==================== TC-WS-003：biz_code 分发到对应 Handler ====================

    @Test
    void dispatchMessageRoutesToRegisteredHandler() {
        HivemindWsClient client = new HivemindWsClient(runtimeConfig, objectMapper, List.of(mapElementHandler));

        String payload = """
                {
                  "biz_code": "map_element_create",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {"id": "elem-1", "group_id": "group-a"}
                }""";
        client.dispatchMessage(payload);

        verify(mapElementHandler).handle(any(JsonNode.class));
    }

    @Test
    void dispatchMessageDoesNotRouteUnregisteredBizCode() {
        HivemindWsClient client = new HivemindWsClient(runtimeConfig, objectMapper, List.of(mapElementHandler));

        // tsa_event 未注册
        String payload = """
                {
                  "biz_code": "tsa_event",
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {}
                }""";
        client.dispatchMessage(payload);

        verify(mapElementHandler, never()).handle(any());
    }

    @Test
    void dispatchMessageDoesNotRouteWhenBizCodeMissing() {
        HivemindWsClient client = new HivemindWsClient(runtimeConfig, objectMapper, List.of(mapElementHandler));

        String payload = """
                {
                  "version": "1.0",
                  "timestamp": 146052438362,
                  "data": {}
                }""";
        client.dispatchMessage(payload);

        verify(mapElementHandler, never()).handle(any());
    }

    @Test
    void dispatchMessageDoesNotThrowOnInvalidJson() {
        HivemindWsClient client = new HivemindWsClient(runtimeConfig, objectMapper, List.of(mapElementHandler));

        // 无效 JSON 不应抛异常
        assertDoesNotThrow(() -> client.dispatchMessage("not a json"));
        verify(mapElementHandler, never()).handle(any());
    }

    @Test
    void dispatchMessageRoutesToCorrectHandlerWhenMultipleRegistered() {
        WsMessageHandler tsaHandler = Mockito.mock(WsMessageHandler.class);
        when(tsaHandler.supportedBizCodes()).thenReturn(Set.of("tsa_event", "tsa_event_push"));

        HivemindWsClient client = new HivemindWsClient(runtimeConfig, objectMapper,
                List.of(mapElementHandler, tsaHandler));

        // 路由到 mapElementHandler
        client.dispatchMessage("""
                {"biz_code": "map_element_create", "data": {}}""");
        verify(mapElementHandler).handle(any());
        verify(tsaHandler, never()).handle(any());

        // 路由到 tsaHandler
        Mockito.reset(mapElementHandler, tsaHandler);
        client.dispatchMessage("""
                {"biz_code": "tsa_event", "data": {}}""");
        verify(tsaHandler).handle(any());
        verify(mapElementHandler, never()).handle(any());
    }

    // ==================== 构造与状态 ====================

    @Test
    void disconnectSetsConnectedFalse() {
        when(runtimeConfig.getHivemindWsUrl()).thenReturn("");
        HivemindWsClient client = new HivemindWsClient(runtimeConfig, objectMapper, List.of(mapElementHandler));

        // 初始状态
        assertFalse(client.isConnected());

        // disconnect 后仍为 false
        client.disconnect();
        assertFalse(client.isConnected());
    }

    @Test
    void constructorWithEmptyHandlersDoesNotThrow() {
        HivemindWsClient client = new HivemindWsClient(runtimeConfig, objectMapper, List.of());

        // 未注册任何 Handler，消息会被忽略但不抛异常
        assertDoesNotThrow(() -> client.dispatchMessage("""
                {"biz_code": "any_code", "data": {}}"""));
    }
}
