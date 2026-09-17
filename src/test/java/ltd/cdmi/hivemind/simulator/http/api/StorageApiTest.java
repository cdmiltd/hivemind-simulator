// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.

package ltd.cdmi.hivemind.simulator.http.api;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link StorageApi} 单元测试。
 * <p>验证 STS 接口路径前缀为 {@code /storage/api/v1/workspaces}（与媒体/地图/设备拓扑不同）。
 */
@DisplayName("StorageApi - 获取上传临时凭证")
class StorageApiTest {

    private HivemindHttpClient httpClient;
    private RuntimeConfig runtimeConfig;
    private StorageApi storageApi;

    @BeforeEach
    void setUp() {
        httpClient = mock(HivemindHttpClient.class);
        runtimeConfig = mock(RuntimeConfig.class);
        when(runtimeConfig.getOrganizationId()).thenReturn("test-ws");
        storageApi = new StorageApi(httpClient, runtimeConfig);
    }

    @Test
    @DisplayName("getSts 调用 POST /storage/api/v1/workspaces/{ws}/sts")
    void getStsCallsCorrectPath() {
        storageApi.getSts();

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).post(pathCaptor.capture(), eq(null));
        assertEquals("/storage/api/v1/workspaces/test-ws/sts", pathCaptor.getValue());
    }

    @Test
    @DisplayName("getSts 路径前缀为 /storage/api/（非 /media/api/ 或 /manage/api/）")
    void getStsUsesStorageApiPrefix() {
        storageApi.getSts();

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).post(pathCaptor.capture(), any());
        assertTrue(pathCaptor.getValue().startsWith("/storage/api/v1/workspaces/"),
                "STS 接口应使用 /storage/api/ 前缀");
    }

    @Test
    @DisplayName("workspace_id 为空时使用 default")
    void getStsUsesDefaultWorkspaceWhenEmpty() {
        when(runtimeConfig.getOrganizationId()).thenReturn("");
        storageApi.getSts();

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).post(pathCaptor.capture(), any());
        assertTrue(pathCaptor.getValue().contains("/default/sts"));
    }
}
