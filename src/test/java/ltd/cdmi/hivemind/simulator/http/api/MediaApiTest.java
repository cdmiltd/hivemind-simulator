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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link MediaApi} 单元测试。
 * <p>验证 4 个媒体管理接口的路径前缀和请求体传递。
 */
@DisplayName("MediaApi - 媒体管理 HTTP 接口")
class MediaApiTest {

    private HivemindHttpClient httpClient;
    private RuntimeConfig runtimeConfig;
    private MediaApi mediaApi;

    @BeforeEach
    void setUp() {
        httpClient = mock(HivemindHttpClient.class);
        runtimeConfig = mock(RuntimeConfig.class);
        when(runtimeConfig.getOrganizationId()).thenReturn("test-ws");
        mediaApi = new MediaApi(httpClient, runtimeConfig);
    }

    @Test
    @DisplayName("fastUpload 调用 POST /media/api/v1/workspaces/{ws}/fast-upload")
    void fastUploadCallsCorrectPath() {
        Object body = new Object();
        mediaApi.fastUpload(body);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).post(pathCaptor.capture(), eq(body));
        assertEquals("/media/api/v1/workspaces/test-ws/fast-upload", pathCaptor.getValue());
    }

    @Test
    @DisplayName("getTinyFingerprints 调用 POST /media/api/v1/workspaces/{ws}/files/tiny-fingerprints")
    void getTinyFingerprintsCallsCorrectPath() {
        List<String> fingerprints = List.of("fp1", "fp2");
        mediaApi.getTinyFingerprints(fingerprints);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).post(pathCaptor.capture(), eq(fingerprints));
        assertEquals("/media/api/v1/workspaces/test-ws/files/tiny-fingerprints", pathCaptor.getValue());
    }

    @Test
    @DisplayName("uploadCallback 调用 POST /media/api/v1/workspaces/{ws}/upload-callback")
    void uploadCallbackCallsCorrectPath() {
        Object body = new Object();
        mediaApi.uploadCallback(body);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).post(pathCaptor.capture(), eq(body));
        assertEquals("/media/api/v1/workspaces/test-ws/upload-callback", pathCaptor.getValue());
    }

    @Test
    @DisplayName("groupUploadCallback 调用 POST /media/api/v1/workspaces/{ws}/group-upload-callback")
    void groupUploadCallbackCallsCorrectPath() {
        Object body = new Object();
        mediaApi.groupUploadCallback(body);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).post(pathCaptor.capture(), eq(body));
        assertEquals("/media/api/v1/workspaces/test-ws/group-upload-callback", pathCaptor.getValue());
    }

    @Test
    @DisplayName("所有接口路径前缀为 /media/api/（非 /storage/api/ 或 /manage/api/）")
    void allPathsUseMediaApiPrefix() {
        mediaApi.fastUpload(new Object());
        mediaApi.getTinyFingerprints(List.of());
        mediaApi.uploadCallback(new Object());
        mediaApi.groupUploadCallback(new Object());

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient, times(4)).post(pathCaptor.capture(), any());
        pathCaptor.getAllValues().forEach(path ->
                assertTrue(path.startsWith("/media/api/v1/workspaces/"),
                        "媒体接口应使用 /media/api/ 前缀: " + path));
    }
}
