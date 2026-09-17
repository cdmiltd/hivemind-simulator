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

package ltd.cdmi.hivemind.simulator.handler;

import com.fasterxml.jackson.databind.JsonNode;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import ltd.cdmi.hivemind.simulator.http.api.MediaApi;
import ltd.cdmi.hivemind.simulator.http.api.StorageApi;
import ltd.cdmi.hivemind.simulator.http.api.WaylineApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pilot 上云 HTTP 接口模拟协调器。
 * <p>协调媒体管理和航线管理 HTTP API 的调用，提供统一的入口供前端 REST API 测试。
 * <p>与 {@link MapElementSimulator}（地图元素）和 {@link SituationAwarenessSimulator}（态势感知）平级，
 * 共同构成 Pilot 上云的 HTTP 接口模拟层。
 * <p>生命周期：Pilot 上线时不主动触发（媒体/航线接口由前端按需调用），仅作为 API 调用入口。
 */
@Component
public class PilotHttpSimulator {

    private static final Logger log = LoggerFactory.getLogger(PilotHttpSimulator.class);

    private final MediaApi mediaApi;
    private final StorageApi storageApi;
    private final WaylineApi waylineApi;

    public PilotHttpSimulator(MediaApi mediaApi, StorageApi storageApi, WaylineApi waylineApi) {
        this.mediaApi = mediaApi;
        this.storageApi = storageApi;
        this.waylineApi = waylineApi;
    }

    // ==================== 媒体管理 ====================

    /** 文件快传（秒传） */
    public HivemindHttpClient.HivemindResponse fastUpload(JsonNode body) {
        return mediaApi.fastUpload(body);
    }

    /** 获取已存在的精简指纹 */
    public HivemindHttpClient.HivemindResponse getTinyFingerprints(List<String> tinyFingerprints) {
        return mediaApi.getTinyFingerprints(tinyFingerprints);
    }

    /** 媒体文件上传结果上报 */
    public HivemindHttpClient.HivemindResponse mediaUploadCallback(JsonNode body) {
        return mediaApi.uploadCallback(body);
    }

    /** 文件组上传完成后回调 */
    public HivemindHttpClient.HivemindResponse groupUploadCallback(JsonNode body) {
        return mediaApi.groupUploadCallback(body);
    }

    // ==================== 存储服务 ====================

    /** 获取上传临时凭证（STS） */
    public HivemindHttpClient.HivemindResponse getSts() {
        return storageApi.getSts();
    }

    // ==================== 航线管理 ====================

    /** 获取航线文件列表 */
    public HivemindHttpClient.HivemindResponse getWaylines(
            Boolean favorited, String orderBy, Integer page, Integer pageSize,
            List<Integer> templateTypes, Integer actionType,
            List<String> droneModelKeys, List<String> payloadModelKeys) {
        return waylineApi.getWaylines(favorited, orderBy, page, pageSize,
                templateTypes, actionType, droneModelKeys, payloadModelKeys);
    }

    /** 获取航线文件下载地址 */
    public HivemindHttpClient.HivemindResponse getWaylineUrl(String waylineId) {
        return waylineApi.getWaylineUrl(waylineId);
    }

    /** 获取重复的航线文件名称 */
    public HivemindHttpClient.HivemindResponse getDuplicateWaylineNames(List<String> names) {
        return waylineApi.getDuplicateNames(names);
    }

    /** 航线文件上传结果上报 */
    public HivemindHttpClient.HivemindResponse waylineUploadCallback(JsonNode body) {
        return waylineApi.uploadCallback(body);
    }

    /** 批量收藏航线文件 */
    public HivemindHttpClient.HivemindResponse addWaylineFavorites(List<String> waylineIds) {
        return waylineApi.addFavorites(waylineIds);
    }

    /** 批量取消收藏航线文件 */
    public HivemindHttpClient.HivemindResponse removeWaylineFavorites(List<String> waylineIds) {
        return waylineApi.removeFavorites(waylineIds);
    }
}
