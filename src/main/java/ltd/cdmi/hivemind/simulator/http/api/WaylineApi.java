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
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 航线管理 HTTP API。
 * <p>实现 DJI Pilot 上云航线管理的 6 个接口，调用 hivemind Server API。
 * <p>接口路径遵循 DJI 官方规范（路径前缀 {@code /wayline/api/v1/workspaces}）：
 * <ul>
 *   <li>GET  /wayline/api/v1/workspaces/{workspace_id}/waylines — 获取航线文件列表</li>
 *   <li>GET  /wayline/api/v1/workspaces/{workspace_id}/waylines/{id}/url — 获取航线文件下载地址</li>
 *   <li>GET  /wayline/api/v1/workspaces/{workspace_id}/waylines/duplicate-names — 获取重复的航线文件名称</li>
 *   <li>POST /wayline/api/v1/workspaces/{workspace_id}/upload-callback — 航线文件上传结果上报</li>
 *   <li>POST /wayline/api/v1/workspaces/{workspace_id}/favorites — 批量收藏航线文件</li>
 *   <li>DELETE /wayline/api/v1/workspaces/{workspace_id}/favorites — 批量取消收藏航线文件</li>
 * </ul>
 * <p>注意：路径前缀为 {@code /wayline/api/v1/workspaces}，与其他 API 类不同（DJI 文档规定）。
 */
@Component
public class WaylineApi {

    private static final Logger log = LoggerFactory.getLogger(WaylineApi.class);

    /** 航线管理 API 路径前缀（委托 SDK {@link HttpApiPath#WAYLINE_BASE_PATH}） */
    private static final String BASE_PATH = HttpApiPath.WAYLINE_BASE_PATH;

    private final HivemindHttpClient httpClient;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;

    public WaylineApi(HivemindHttpClient httpClient, RuntimeConfig runtimeConfig,
                      DiagnosticLogRecorder diagnosticRecorder) {
        this.httpClient = httpClient;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
    }

    /**
     * 获取航线文件列表。
     *
     * @param favorited 是否收藏（null 表示不筛选）
     * @param orderBy 排序（如 "update_time desc"，null 表示不排序）
     * @param page 页数（null 表示不传）
     * @param pageSize 每页大小（null 表示不传）
     * @param templateTypes 航线模版类型集合（null 表示不筛选）
     * @param actionType 1=开启精准复拍航线（null 表示不筛选）
     * @param droneModelKeys 机型筛选（null 表示不筛选）
     * @param payloadModelKeys 负载筛选（null 表示不筛选）
     */
    public HivemindHttpClient.HivemindResponse getWaylines(
            Boolean favorited, String orderBy, Integer page, Integer pageSize,
            List<Integer> templateTypes, Integer actionType,
            List<String> droneModelKeys, List<String> payloadModelKeys) {

        String path = BASE_PATH + "/" + getWorkspaceId() + "/waylines";
        Map<String, Object> queryParams = new LinkedHashMap<>();

        if (favorited != null) queryParams.put("favorited", favorited);
        if (orderBy != null && !orderBy.isBlank()) queryParams.put("order_by", orderBy);
        if (page != null) queryParams.put("page", page);
        if (pageSize != null) queryParams.put("page_size", pageSize);
        if (templateTypes != null && !templateTypes.isEmpty()) queryParams.put("template_type", templateTypes);
        if (actionType != null) queryParams.put("action_type", actionType);
        if (droneModelKeys != null && !droneModelKeys.isEmpty()) queryParams.put("drone_model_keys", droneModelKeys);
        if (payloadModelKeys != null && !payloadModelKeys.isEmpty()) queryParams.put("payload_model_key", payloadModelKeys);

        Map<String, Object> finalParams = queryParams.isEmpty() ? null : queryParams;
        log.info("获取航线文件列表: favorited={}, page={}, page_size={}", favorited, page, pageSize);
        return httpClient.get(path, finalParams);
    }

    /**
     * 获取航线文件下载地址。
     * <p>注意：DJI 文档未提供响应 Schema，实现时透传 hivemind 响应。
     *
     * @param waylineId 航线文件id
     */
    public HivemindHttpClient.HivemindResponse getWaylineUrl(String waylineId) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/waylines/" + waylineId + "/url";
        log.info("获取航线文件下载地址: wayline_id={}", waylineId);

        // M-2 诊断日志：DJI 文档未提供响应 Schema，实现为透传响应，待真机验证响应结构
        diagnosticRecorder.record(
                DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                "getWaylineUrl",
                "获取航线文件下载地址：DJI 文档未提供响应 Schema，实现为透传 hivemind 响应。" +
                "待真机验证响应结构（推断为 {code, message, data:{url}}）。");

        return httpClient.get(path, null);
    }

    /**
     * 获取重复的航线文件名称。
     * <p>上传航线文件前检查是否有重名文件。
     *
     * @param names 文件名称集合（必填）
     */
    public HivemindHttpClient.HivemindResponse getDuplicateNames(List<String> names) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/waylines/duplicate-names";
        Map<String, Object> queryParams = new LinkedHashMap<>();
        if (names != null && !names.isEmpty()) {
            queryParams.put("name", names);
        }
        log.info("获取重复的航线文件名称: count={}", names != null ? names.size() : 0);
        return httpClient.get(path, queryParams.isEmpty() ? null : queryParams);
    }

    /**
     * 航线文件上传结果上报。
     *
     * @param body wayline.UploadCallbackInput（object_key 必填，name/metadata 可选）
     */
    public HivemindHttpClient.HivemindResponse uploadCallback(Object body) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/upload-callback";
        log.info("航线文件上传结果上报");
        return httpClient.post(path, body);
    }

    /**
     * 批量收藏航线文件。
     * <p>注意：DJI 文档将 id 参数标注为 path 类型，但路径中无 {id} 占位符。
     * 推断为 body 参数，请求体为 {id: ["uuid1", "uuid2"]}。
     *
     * @param waylineIds 航线文件 ID 集合
     */
    public HivemindHttpClient.HivemindResponse addFavorites(List<String> waylineIds) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/favorites";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", waylineIds);
        log.info("批量收藏航线文件: count={}", waylineIds != null ? waylineIds.size() : 0);

        // M-2 诊断日志：DJI 文档参数位置矛盾（标 path 但路径无占位符），推断为 body 参数
        diagnosticRecorder.record(
                DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                "addFavorites",
                "批量收藏航线文件：DJI 文档将 id 参数标注为 path 类型，但路径中无 {id} 占位符。" +
                "推断为 body 参数 {id: [...]}，待真机验证。");

        return httpClient.post(path, body);
    }

    /**
     * 批量取消收藏航线文件。
     *
     * @param waylineIds 航线文件 ID 集合（通过 query 参数传递）
     */
    public HivemindHttpClient.HivemindResponse removeFavorites(List<String> waylineIds) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/favorites";
        Map<String, Object> queryParams = new LinkedHashMap<>();
        if (waylineIds != null && !waylineIds.isEmpty()) {
            queryParams.put("id", waylineIds);
        }
        log.info("批量取消收藏航线文件: count={}", waylineIds != null ? waylineIds.size() : 0);
        return httpClient.delete(path, queryParams.isEmpty() ? null : queryParams);
    }

    private String getWorkspaceId() {
        // workspace_id 使用组织 ID（Pilot 注册时由用户提供），与其他 API 类一致
        String wsId = runtimeConfig.getOrganizationId();
        return wsId != null && !wsId.isBlank() ? wsId : "default";
    }
}
