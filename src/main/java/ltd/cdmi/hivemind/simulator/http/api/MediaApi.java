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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 媒体管理 HTTP API。
 * <p>实现 DJI Pilot 上云媒体管理的 4 个接口，调用 hivemind Server API。
 * <p>接口路径遵循 DJI 官方规范（路径前缀 {@code /media/api/v1/workspaces}）：
 * <ul>
 *   <li>POST /media/api/v1/workspaces/{workspace_id}/fast-upload — 文件快传（秒传）</li>
 *   <li>POST /media/api/v1/workspaces/{workspace_id}/files/tiny-fingerprints — 获取已存在的精简指纹</li>
 *   <li>POST /media/api/v1/workspaces/{workspace_id}/upload-callback — 媒体文件上传结果上报</li>
 *   <li>POST /media/api/v1/workspaces/{workspace_id}/group-upload-callback — 文件组上传完成后回调</li>
 * </ul>
 * <p>注意：路径前缀为 {@code /media/api/v1/workspaces}，与 {@link MapElementApi}（{@code /map/api/v1/workspaces}）、
 * {@link DeviceTopoApi}（{@code /manage/api/v1/workspaces}）、{@link StorageApi}（{@code /storage/api/v1/workspaces}）、
 * {@link WaylineApi}（{@code /wayline/api/v1/workspaces}）不同（DJI 文档规定）。
 * <p>与机场上云的 {@link ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator} 完全独立：
 * 机场上云走 MQTT（storage_config_get → 对象存储上传 → file_upload_callback），
 * Pilot 上云走 HTTP（文件快传 → 获取 STS 凭证 → 文件上传 → 上传回调）。
 */
@Component
public class MediaApi {

    private static final Logger log = LoggerFactory.getLogger(MediaApi.class);

    /** 媒体管理 API 路径前缀 */
    private static final String BASE_PATH = "/media/api/v1/workspaces";

    private final HivemindHttpClient httpClient;
    private final RuntimeConfig runtimeConfig;

    public MediaApi(HivemindHttpClient httpClient, RuntimeConfig runtimeConfig) {
        this.httpClient = httpClient;
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * 文件快传（秒传）。
     * <p>Pilot 用文件指纹查询 hivemind 是否已存在该文件：
     * <ul>
     *   <li>data 为空：文件不存在，需继续走正常上传流程</li>
     *   <li>data 非空：文件已存在，秒传成功，跳过上传</li>
     * </ul>
     *
     * @param body media.FastUploadInput（fingerprint 必填，ext/name/path 可选）
     */
    public HivemindHttpClient.HivemindResponse fastUpload(Object body) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/fast-upload";
        log.info("文件快传");
        return httpClient.post(path, body);
    }

    /**
     * 获取已存在的精简指纹。
     * <p>Pilot 提交一批精简指纹，hivemind 返回其中已存在的精简指纹列表，用于批量秒传判断。
     *
     * @param tinyFingerprints 精简指纹数组（必填）
     */
    public HivemindHttpClient.HivemindResponse getTinyFingerprints(java.util.List<String> tinyFingerprints) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/files/tiny-fingerprints";
        log.info("获取已存在的精简指纹: count={}", tinyFingerprints != null ? tinyFingerprints.size() : 0);
        return httpClient.post(path, tinyFingerprints);
    }

    /**
     * 媒体文件上传结果上报。
     * <p>Pilot 上传文件到对象存储后，向 hivemind 回调上报结果。
     *
     * @param body media.UploadCallbackInput（result/name/object_key/path 必填）
     */
    public HivemindHttpClient.HivemindResponse uploadCallback(Object body) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/upload-callback";
        log.info("媒体文件上传结果上报");
        return httpClient.post(path, body);
    }

    /**
     * 文件组上传完成后回调。
     * <p>整个文件组上传完成后，向 hivemind 回调通知文件组的上传情况。
     *
     * @param body storage.FolderUploadCallbackInput（file_count/file_uploaded_count 必填）
     */
    public HivemindHttpClient.HivemindResponse groupUploadCallback(Object body) {
        String path = BASE_PATH + "/" + getWorkspaceId() + "/group-upload-callback";
        log.info("文件组上传完成后回调");
        return httpClient.post(path, body);
    }

    private String getWorkspaceId() {
        // workspace_id 使用组织 ID（Pilot 注册时由用户提供），与其他 API 类一致
        String wsId = runtimeConfig.getOrganizationId();
        return wsId != null && !wsId.isBlank() ? wsId : "default";
    }
}
