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

import com.fasterxml.jackson.databind.JsonNode;
import ltd.cdmi.dji.cloudapi.sdk.http.HttpApiPath;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 地图元素 HTTP API。
 * <p>实现 DJI Pilot 上云地图元素的 CRUD 接口，调用 hivemind Server API。
 * <p>接口路径遵循 DJI 官方规范：
 * <ul>
 *   <li>GET /map/api/v1/workspaces/{workspace_id}/element-groups — 获取元素列表</li>
 *   <li>POST /map/api/v1/workspaces/{workspace_id}/element-groups/{group_id}/elements — 创建元素</li>
 *   <li>PUT /map/api/v1/workspaces/{workspace_id}/elements/{id} — 更新元素</li>
 *   <li>DELETE /map/api/v1/workspaces/{workspace_id}/elements/{id} — 删除元素</li>
 * </ul>
 */
@Component
public class MapElementApi {

    private static final Logger log = LoggerFactory.getLogger(MapElementApi.class);

    /** 地图元素 API 路径前缀（委托 SDK {@link HttpApiPath#MAP_BASE_PATH}） */
    private static final String BASE_PATH = HttpApiPath.MAP_BASE_PATH;

    private final HivemindHttpClient httpClient;
    private final RuntimeConfig runtimeConfig;

    public MapElementApi(HivemindHttpClient httpClient, RuntimeConfig runtimeConfig) {
        this.httpClient = httpClient;
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * 获取地图元素列表（Pilot 首次上线或收到 WebSocket 刷新图层推送时调用）。
     *
     * @param groupId 元素组id（null 或空则返回所有元素，指定则返回该元素组内的元素集合）
     * @param isDistributed 元素组分发状态（null 表示不传该参数，由服务端使用默认值 true）
     */
    public HivemindHttpClient.HivemindResponse getElements(String groupId, Boolean isDistributed) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/element-groups";
        Map<String, Object> queryParams = null;
        if ((groupId != null && !groupId.isBlank()) || isDistributed != null) {
            queryParams = new LinkedHashMap<>();
            if (groupId != null && !groupId.isBlank()) {
                queryParams.put("group_id", groupId);
            }
            if (isDistributed != null) {
                queryParams.put("is_distributed", isDistributed);
            }
        }
        log.info("获取地图元素列表: group_id={}, is_distributed={}", groupId, isDistributed);
        return httpClient.get(path, queryParams);
    }

    /**
     * 获取地图元素列表（简化重载，不传 is_distributed）。
     * <p>保持向后兼容，等价于 {@link #getElements(String, Boolean) getElements(groupId, null)}。
     */
    public HivemindHttpClient.HivemindResponse getElements(String groupId) {
        return getElements(groupId, null);
    }

    /** 创建地图元素 */
    public HivemindHttpClient.HivemindResponse createElement(String groupId, Object element) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/element-groups/" + groupId + "/elements";
        log.info("创建地图元素: group_id={}", groupId);
        return httpClient.post(path, element);
    }

    /** 更新地图元素 */
    public HivemindHttpClient.HivemindResponse updateElement(String elementId, Object element) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/elements/" + elementId;
        log.info("更新地图元素: element_id={}", elementId);
        return httpClient.put(path, element);
    }

    /** 删除地图元素 */
    public HivemindHttpClient.HivemindResponse deleteElement(String elementId) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/elements/" + elementId;
        log.info("删除地图元素: element_id={}", elementId);
        return httpClient.delete(path);
    }

    private String getWorkspaceId() {
        // workspace_id 使用组织 ID（Pilot 注册时由用户提供）
        String wsId = runtimeConfig.getOrganizationId();
        return wsId != null && !wsId.isBlank() ? wsId : "default";
    }
}
