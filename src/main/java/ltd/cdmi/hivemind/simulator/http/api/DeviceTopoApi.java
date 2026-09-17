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

import ltd.cdmi.dji.cloudapi.sdk.http.HttpApiPath;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 设备拓扑 HTTP API（态势感知）。
 * <p>实现 DJI Pilot 上云"获取设备拓扑列表"接口，调用 hivemind Server API。
 * <p>接口路径遵循 DJI 官方规范：
 * <ul>
 *   <li>GET /manage/api/v1/workspaces/{workspace_id}/devices/topologies — 获取设备拓扑列表</li>
 * </ul>
 * <p>注意：路径前缀为 {@code /manage/api/v1/workspaces}，与 {@link MapElementApi} 的
 * {@code /map/api/v1/workspaces} 不同（DJI 文档规定）。
 * <p>触发时机：
 * <ul>
 *   <li>Pilot 首次上线后主动调用一次（DJI 文档要求）</li>
 *   <li>收到 WebSocket {@code device_online/device_offline/device_update_topo} 推送时调用</li>
 * </ul>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/https/situation-awareness/obtain-device-topology-list.html">
 * DJI 获取设备拓扑列表</a>
 */
@Component
public class DeviceTopoApi {

    private static final Logger log = LoggerFactory.getLogger(DeviceTopoApi.class);

    /** 态势感知 API 路径前缀（委托 SDK {@link HttpApiPath#MANAGE_BASE_PATH}） */
    private static final String BASE_PATH = HttpApiPath.MANAGE_BASE_PATH;

    private final HivemindHttpClient httpClient;
    private final RuntimeConfig runtimeConfig;

    public DeviceTopoApi(HivemindHttpClient httpClient, RuntimeConfig runtimeConfig) {
        this.httpClient = httpClient;
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * 获取设备拓扑列表。
     * <p>Pilot 首次上线或收到 WebSocket 设备上线/下线/拓扑更新推送时调用。
     *
     * @return hivemind 响应（data.list 包含 hosts 和 parents 设备拓扑集合）
     */
    public HivemindHttpClient.HivemindResponse getDeviceTopo() {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/devices/topologies";
        log.info("获取设备拓扑列表: workspace_id={}", getWorkspaceId());
        return httpClient.get(path, null);
    }

    private String getWorkspaceId() {
        // workspace_id 使用组织 ID（Pilot 注册时由用户提供），与 MapElementApi 一致
        String wsId = runtimeConfig.getOrganizationId();
        return wsId != null && !wsId.isBlank() ? wsId : "default";
    }
}
