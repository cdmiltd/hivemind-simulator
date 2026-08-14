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

package ltd.cdmi.hivemind.simulator.http.api;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link DeviceTopoApi} 单元测试。
 * <p>对应 TDD-SPEC TC-TSA-009：路径前缀为 /manage/api/v1/workspaces（与 MapElementApi 的 /map/api/v1/workspaces 不同）。
 */
class DeviceTopoApiTest {

    private HivemindHttpClient httpClient;
    private RuntimeConfig runtimeConfig;
    private DeviceTopoApi deviceTopoApi;

    @BeforeEach
    void setUp() {
        httpClient = mock(HivemindHttpClient.class);
        runtimeConfig = mock(RuntimeConfig.class);
        deviceTopoApi = new DeviceTopoApi(httpClient, runtimeConfig);
    }

    // ==================== TC-TSA-009：路径前缀与 MapElementApi 不同 ====================

    @Test
    void getDeviceTopoUsesCorrectPathPrefix() {
        when(runtimeConfig.getOrganizationId()).thenReturn("ws-123");
        when(httpClient.get(anyString(), any())).thenReturn(
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null));

        deviceTopoApi.getDeviceTopo();

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient, times(1)).get(pathCaptor.capture(), any());
        String path = pathCaptor.getValue();

        // 路径前缀必须是 /manage/api/v1/workspaces（与 MapElementApi 的 /map/api/v1/workspaces 不同）
        assertTrue(path.startsWith("/manage/api/v1/workspaces/"),
                "路径前缀应为 /manage/api/v1/workspaces，实际: " + path);
        assertTrue(path.endsWith("/devices/topologies"),
                "路径应以 /devices/topologies 结尾，实际: " + path);
        assertEquals("/manage/api/v1/workspaces/ws-123/devices/topologies", path);
    }

    // ==================== workspace_id 缺失时使用默认值 ====================

    @Test
    void getDeviceTopoUsesDefaultWorkspaceIdWhenMissing() {
        when(runtimeConfig.getOrganizationId()).thenReturn("");
        when(httpClient.get(anyString(), any())).thenReturn(
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null));

        deviceTopoApi.getDeviceTopo();

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient, times(1)).get(pathCaptor.capture(), any());
        assertEquals("/manage/api/v1/workspaces/default/devices/topologies",
                pathCaptor.getValue());
    }

    @Test
    void getDeviceTopoUsesDefaultWorkspaceIdWhenNull() {
        when(runtimeConfig.getOrganizationId()).thenReturn(null);
        when(httpClient.get(anyString(), any())).thenReturn(
                new HivemindHttpClient.HivemindResponse(true, 0, "success", null));

        deviceTopoApi.getDeviceTopo();

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient, times(1)).get(pathCaptor.capture(), any());
        assertEquals("/manage/api/v1/workspaces/default/devices/topologies",
                pathCaptor.getValue());
    }
}
