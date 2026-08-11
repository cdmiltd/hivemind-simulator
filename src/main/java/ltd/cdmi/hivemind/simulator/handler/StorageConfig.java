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

/**
 * 对象存储临时凭证（解析自 storage_config_get 回复）。
 * <p>封装 DJI Cloud API storage_config_get 返回的 STS 凭证和 bucket 信息，
 * 供 {@link MediaUploader} 通过 S3 兼容协议上传文件。</p>
 *
 * <p>回复结构（requests_reply）：</p>
 * <pre>
 * data.output.bucket               — 对象存储桶名称
 * data.output.credentials.access_key_id     — 访问密钥 ID
 * data.output.credentials.access_key_secret — 秘密访问密钥
 * data.output.credentials.security_token    — 会话凭证
 * data.output.credentials.expire            — 过期时间（秒）
 * data.output.endpoint              — 对外服务的访问域名
 * data.output.provider              — 云厂商（ali/aws/minio，DJI 协议定义；OBS 等扩展值也兼容）
 * data.output.region                — 数据中心地域
 * data.output.object_key_prefix     — 对象存储 Key 前缀
 * </pre>
 *
 * <p>核实依据：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html">DJI 媒体管理 MQTT 接口</a>
 * 中「获取上传临时凭证」storage_config_get 的 requests_reply 结构。</p>
 *
 * @param bucket          对象存储桶名称
 * @param endpoint        对外服务的访问域名（如 https://oss-cn-hangzhou.aliyuncs.com）
 * @param region          数据中心地域（如 hz、us-east-1）
 * @param provider        云厂商枚举（ali/aws/minio，DJI 协议定义；OBS 等扩展值也兼容）
 * @param objectKeyPrefix 对象存储 Key 前缀
 * @param accessKeyId     访问密钥 ID
 * @param accessKeySecret 秘密访问密钥
 * @param securityToken   会话凭证（STS Token）
 * @param expire          凭证过期时间（秒）
 */
public record StorageConfig(
        String bucket,
        String endpoint,
        String region,
        String provider,
        String objectKeyPrefix,
        String accessKeyId,
        String accessKeySecret,
        String securityToken,
        long expire
) {

    /**
     * 从 storage_config_get 的 requests_reply JSON 解析凭证。
     * @param reply requests_reply 完整 JSON（含 data.output.* 结构）
     * @return 解析后的 StorageConfig；reply 为 null 或 result≠0 返回 null
     */
    public static StorageConfig fromReply(JsonNode reply) {
        if (reply == null) {
            return null;
        }
        JsonNode output = reply.path("data").path("output");
        int result = reply.path("data").path("result").asInt(-1);
        if (result != 0 || output.isMissingNode()) {
            return null;
        }

        JsonNode creds = output.path("credentials");
        return new StorageConfig(
                output.path("bucket").asText(""),
                output.path("endpoint").asText(""),
                output.path("region").asText(""),
                output.path("provider").asText(""),
                output.path("object_key_prefix").asText(""),
                creds.path("access_key_id").asText(""),
                creds.path("access_key_secret").asText(""),
                creds.path("security_token").asText(""),
                creds.path("expire").asLong(0)
        );
    }

    /**
     * 凭证是否有效（关键字段非空）。
     * <p>用于判断是否可执行 S3 上传：bucket、endpoint、accessKeyId、accessKeySecret 缺一不可。
     * securityToken 可为空（部分 MinIO 配置不强制 STS）。</p>
     */
    public boolean isValid() {
        return !bucket.isBlank()
                && !endpoint.isBlank()
                && !accessKeyId.isBlank()
                && !accessKeySecret.isBlank();
    }
}
