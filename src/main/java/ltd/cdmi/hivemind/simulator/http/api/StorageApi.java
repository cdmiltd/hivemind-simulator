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
 * 存储服务 HTTP API。
 * <p>实现 DJI Pilot 上云"获取上传临时凭证（STS）"接口，调用 hivemind Server API。
 * <p>此接口为通用存储服务，被多个功能域共用：
 * <ul>
 *   <li>媒体管理：上传媒体文件前获取 STS 凭证</li>
 *   <li>航线管理：上传航线文件前获取 STS 凭证</li>
 * </ul>
 * <p>接口路径遵循 DJI 官方规范：
 * <ul>
 *   <li>POST /storage/api/v1/workspaces/{workspace_id}/sts — 生成上传文件临时凭证</li>
 * </ul>
 * <p>注意：路径前缀为 {@code /storage/api/v1/workspaces}，与 {@link MapElementApi}（{@code /map/api/v1/workspaces}）、
 * {@link DeviceTopoApi}（{@code /manage/api/v1/workspaces}）不同（DJI 文档规定）。
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/https/media-management/generate-upload-credentials.html">
 * DJI 生成上传文件临时凭证</a>
 */
@Component
public class StorageApi {

    private static final Logger log = LoggerFactory.getLogger(StorageApi.class);

    /** 存储服务 API 路径前缀（委托 SDK {@link HttpApiPath#STORAGE_BASE_PATH}） */
    private static final String BASE_PATH = HttpApiPath.STORAGE_BASE_PATH;

    private final HivemindHttpClient httpClient;
    private final RuntimeConfig runtimeConfig;

    public StorageApi(HivemindHttpClient httpClient, RuntimeConfig runtimeConfig) {
        this.httpClient = httpClient;
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * 生成上传文件临时凭证（STS）。
     * <p>媒体管理和航线管理上传文件前均调用此接口获取对象存储临时凭证。
     *
     * @return hivemind 响应（data 包含 bucket/credentials/endpoint/object_key_prefix/provider/region）
     */
    public HivemindHttpClient.HivemindResponse getSts() {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/sts";
        log.info("获取上传临时凭证: workspace_id={}", getWorkspaceId());
        return httpClient.post(path, null);
    }

    private String getWorkspaceId() {
        // workspace_id 使用组织 ID（Pilot 注册时由用户提供），与其他 API 类一致
        String wsId = runtimeConfig.getOrganizationId();
        return wsId != null && !wsId.isBlank() ? wsId : "default";
    }
}
