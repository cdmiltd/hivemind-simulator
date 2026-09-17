// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.

package ltd.cdmi.hivemind.simulator.http.api;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link WaylineApi} 单元测试。
 * <p>验证 6 个航线管理接口的路径前缀、查询参数（含数组）和请求体传递。
 */
@DisplayName("WaylineApi - 航线管理 HTTP 接口")
class WaylineApiTest {

    private HivemindHttpClient httpClient;
    private RuntimeConfig runtimeConfig;
    private DiagnosticLogRecorder diagnosticRecorder;
    private WaylineApi waylineApi;

    @BeforeEach
    void setUp() {
        httpClient = mock(HivemindHttpClient.class);
        runtimeConfig = mock(RuntimeConfig.class);
        diagnosticRecorder = mock(DiagnosticLogRecorder.class);
        when(runtimeConfig.getOrganizationId()).thenReturn("test-ws");
        waylineApi = new WaylineApi(httpClient, runtimeConfig, diagnosticRecorder);
    }

    @Test
    @DisplayName("getWaylines 调用 GET /wayline/api/v1/workspaces/{ws}/waylines")
    void getWaylinesCallsCorrectPath() {
        waylineApi.getWaylines(true, "update_time desc", 1, 10,
                null, null, null, null);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).get(pathCaptor.capture(), any());
        assertEquals("/wayline/api/v1/workspaces/test-ws/waylines", pathCaptor.getValue());
    }

    @Test
    @DisplayName("getWaylines 传递所有查询参数")
    @SuppressWarnings("unchecked")
    void getWaylinesPassesAllQueryParams() {
        waylineApi.getWaylines(true, "update_time desc", 1, 10,
                List.of(0, 1), 1, List.of("0-67-0"), List.of("1-53-0"));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).get(anyString(), paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        assertEquals(true, params.get("favorited"));
        assertEquals("update_time desc", params.get("order_by"));
        assertEquals(1, params.get("page"));
        assertEquals(10, params.get("page_size"));
        assertEquals(List.of(0, 1), params.get("template_type"));
        assertEquals(1, params.get("action_type"));
        assertEquals(List.of("0-67-0"), params.get("drone_model_keys"));
        assertEquals(List.of("1-53-0"), params.get("payload_model_key"));
    }

    @Test
    @DisplayName("getWaylines 无参数时 queryParams 为 null")
    void getWaylinesWithNullParamsPassesNullQuery() {
        waylineApi.getWaylines(null, null, null, null, null, null, null, null);
        verify(httpClient).get(anyString(), eq(null));
    }

    @Test
    @DisplayName("getWaylineUrl 调用 GET /wayline/api/v1/workspaces/{ws}/waylines/{id}/url")
    void getWaylineUrlCallsCorrectPath() {
        waylineApi.getWaylineUrl("wayline-123");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).get(pathCaptor.capture(), eq(null));
        assertEquals("/wayline/api/v1/workspaces/test-ws/waylines/wayline-123/url", pathCaptor.getValue());
    }

    @Test
    @DisplayName("getWaylineUrl 记录 M-2 诊断日志（响应 Schema 缺失）")
    void getWaylineUrlRecordsDiagnosticLog() {
        waylineApi.getWaylineUrl("wayline-123");
        verify(diagnosticRecorder).record(eq(ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode.MONITOR_SIMULATOR_INFERENCE), eq("getWaylineUrl"), anyString());
    }

    @Test
    @DisplayName("getDuplicateNames 调用 GET /wayline/api/v1/workspaces/{ws}/waylines/duplicate-names")
    @SuppressWarnings("unchecked")
    void getDuplicateNamesCallsCorrectPath() {
        waylineApi.getDuplicateNames(List.of("航线1", "航线2"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).get(pathCaptor.capture(), any());
        assertEquals("/wayline/api/v1/workspaces/test-ws/waylines/duplicate-names", pathCaptor.getValue());
    }

    @Test
    @DisplayName("getDuplicateNames 查询参数 name 为数组（多值）")
    @SuppressWarnings("unchecked")
    void getDuplicateNamesPassesNameAsArray() {
        waylineApi.getDuplicateNames(List.of("航线1", "航线2"));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).get(anyString(), paramsCaptor.capture());
        assertEquals(List.of("航线1", "航线2"), paramsCaptor.getValue().get("name"));
    }

    @Test
    @DisplayName("uploadCallback 调用 POST /wayline/api/v1/workspaces/{ws}/upload-callback")
    void uploadCallbackCallsCorrectPath() {
        Object body = new Object();
        waylineApi.uploadCallback(body);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).post(pathCaptor.capture(), eq(body));
        assertEquals("/wayline/api/v1/workspaces/test-ws/upload-callback", pathCaptor.getValue());
    }

    @Test
    @DisplayName("addFavorites 调用 POST /wayline/api/v1/workspaces/{ws}/favorites")
    void addFavoritesCallsCorrectPath() {
        waylineApi.addFavorites(List.of("id1", "id2"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).post(pathCaptor.capture(), any());
        assertEquals("/wayline/api/v1/workspaces/test-ws/favorites", pathCaptor.getValue());
    }

    @Test
    @DisplayName("addFavorites 请求体为 {id: [...]}（推断为 body 参数）")
    @SuppressWarnings("unchecked")
    void addFavoritesPassesIdArrayInBody() {
        waylineApi.addFavorites(List.of("id1", "id2"));

        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).post(anyString(), bodyCaptor.capture());
        assertEquals(List.of("id1", "id2"), bodyCaptor.getValue().get("id"));
    }

    @Test
    @DisplayName("addFavorites 记录 M-2 诊断日志（参数位置矛盾）")
    void addFavoritesRecordsDiagnosticLog() {
        waylineApi.addFavorites(List.of("id1"));
        verify(diagnosticRecorder).record(eq(ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode.MONITOR_SIMULATOR_INFERENCE), eq("addFavorites"), anyString());
    }

    @Test
    @DisplayName("removeFavorites 调用 DELETE /wayline/api/v1/workspaces/{ws}/favorites")
    @SuppressWarnings("unchecked")
    void removeFavoritesCallsCorrectPath() {
        waylineApi.removeFavorites(List.of("id1", "id2"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).delete(pathCaptor.capture(), any());
        assertEquals("/wayline/api/v1/workspaces/test-ws/favorites", pathCaptor.getValue());
    }

    @Test
    @DisplayName("removeFavorites 查询参数 id 为数组（多值）")
    @SuppressWarnings("unchecked")
    void removeFavoritesPassesIdAsArrayQuery() {
        waylineApi.removeFavorites(List.of("id1", "id2"));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).delete(anyString(), paramsCaptor.capture());
        assertEquals(List.of("id1", "id2"), paramsCaptor.getValue().get("id"));
    }

    @Test
    @DisplayName("所有接口路径前缀为 /wayline/api/（非 /media/api/ 或 /storage/api/）")
    void allPathsUseWaylineApiPrefix() {
        waylineApi.getWaylines(null, null, null, null, null, null, null, null);
        waylineApi.getWaylineUrl("id");
        waylineApi.getDuplicateNames(List.of());
        waylineApi.uploadCallback(new Object());
        waylineApi.addFavorites(List.of());
        waylineApi.removeFavorites(List.of());

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient, atLeastOnce()).get(pathCaptor.capture(), any());
        verify(httpClient, atLeastOnce()).post(pathCaptor.capture(), any());
        verify(httpClient, atLeastOnce()).delete(pathCaptor.capture(), any());
        pathCaptor.getAllValues().forEach(path ->
                assertTrue(path.startsWith("/wayline/api/v1/workspaces/"),
                        "航线接口应使用 /wayline/api/ 前缀: " + path));
    }
}
