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

package ltd.cdmi.hivemind.simulator.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * S3 兼容协议文件上传器。
 * <p>使用 STS 临时凭证将本地文件上传到对象存储（阿里云 OSS / 华为云 OBS / AWS S3 / MinIO），
 * 供 {@link MediaUploadSimulator} 在媒体上传流程中调用。</p>
 *
 * <p>核实依据：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/pilot-feature-set/pilot-media-management.html">DJI 媒体管理交互时序</a>
 * 中「执行文件上传」步骤——机场使用 storage_config_get 返回的临时凭证直接上传文件到对象存储。</p>
 *
 * <p>Region 解析策略：优先从 endpoint 提取完整区域名（解决 OSS 短格式 region 如 "hz" 导致签名不匹配），
 * 回退到 region 字段，最后默认 us-east-1（MinIO 不校验区域）。</p>
 *
 * <p>每次上传创建独立的 S3Client（STS 凭证有效期短，不同任务凭证不同），
 * 上传完成后关闭客户端释放资源。</p>
 */
@Component
public class MediaUploader {

    private static final Logger log = LoggerFactory.getLogger(MediaUploader.class);

    /** 支持的媒体文件扩展名（图片 + 视频） */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".jpg", ".jpeg", ".png", ".dng", ".tif", ".tiff",
            ".mp4", ".mov", ".avi"
    );

    /**
     * 上传单个文件到对象存储。
     *
     * @param filePath   本地文件路径
     * @param config     STS 凭证配置
     * @param objectKey  对象存储 Key（不含 bucket，含完整路径）
     * @return true=上传成功，false=上传失败（凭证无效、文件不存在、网络异常等）
     */
    public boolean upload(Path filePath, StorageConfig config, String objectKey) {
        if (!config.isValid()) {
            log.warn("STS 凭证无效，跳过文件上传: bucket={}, endpoint={}", config.bucket(), config.endpoint());
            return false;
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            log.warn("文件不存在或非普通文件，跳过上传: {}", filePath);
            return false;
        }

        S3Client client = null;
        try {
            client = buildClient(config);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(objectKey)
                    .build();
            client.putObject(request, filePath);
            log.info("文件上传成功: {} -> bucket={}, key={}", filePath.getFileName(), config.bucket(), objectKey);
            return true;
        } catch (Exception e) {
            log.warn("文件上传失败: {} - {}", filePath.getFileName(), e.getMessage());
            return false;
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * 扫描媒体文件目录，返回支持的图片/视频文件列表。
     * @param mediaDir 媒体文件目录路径
     * @return 文件路径列表（按文件名排序）；目录无效或为空返回空列表
     */
    public List<Path> listMediaFiles(String mediaDir) {
        if (mediaDir == null || mediaDir.isBlank()) {
            return List.of();
        }
        Path dir = Path.of(mediaDir);
        if (!Files.isDirectory(dir)) {
            log.warn("media-dir 不是有效目录: {}", mediaDir);
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("扫描媒体目录失败: {} - {}", mediaDir, e.getMessage());
        }
        return files;
    }

    /**
     * 构建 S3 兼容客户端。
     * <p>按 provider 差异配置：
     * <ul>
     *   <li>minio：启用 path-style 访问（MinIO 不支持 virtual-hosted style）</li>
     *   <li>ali/aws/obs 及其他：使用默认 virtual-hosted style</li>
     * </ul>
     * <p>Region 通过 {@link #resolveRegion} 解析，优先从 endpoint 提取完整区域名。
     */
    private S3Client buildClient(StorageConfig config) {
        String endpoint = ensureScheme(config.endpoint());
        Region region = Region.of(resolveRegion(config));

        AwsSessionCredentials creds = AwsSessionCredentials.create(
                config.accessKeyId(),
                config.accessKeySecret(),
                config.securityToken() != null ? config.securityToken() : "");

        var builder = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(creds));

        // MinIO 必须使用 path-style，OSS/OBS/S3 默认 virtual-hosted
        if ("minio".equals(config.provider())) {
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
        }

        return builder.build();
    }

    /**
     * 解析 S3 签名区域。
     * <p>优先从 endpoint 提取完整区域名（解决 OSS 短格式 region 如 "hz" 导致签名不匹配），
     * 回退到 region 字段，最后默认 us-east-1（MinIO 不校验区域）。</p>
     *
     * <p>提取规则：</p>
     * <ul>
     *   <li>阿里云 OSS：{@code oss-cn-hangzhou.aliyuncs.com} → {@code cn-hangzhou}</li>
     *   <li>华为云 OBS：{@code obs.cn-north-1.myhuaweicloud.com} → {@code cn-north-1}</li>
     *   <li>AWS S3：{@code s3.us-east-1.amazonaws.com} → {@code us-east-1}</li>
     * </ul>
     */
    private String resolveRegion(StorageConfig config) {
        // 优先从 endpoint 提取（解决 OSS region 短格式如 "hz" 导致签名不匹配）
        String extracted = extractRegionFromEndpoint(config.endpoint());
        if (extracted != null && !extracted.isBlank()) {
            return extracted;
        }
        // 回退到 region 字段
        if (config.region() != null && !config.region().isBlank()) {
            return config.region();
        }
        // 默认值（MinIO 等不关心 region 的服务）
        return "us-east-1";
    }

    /**
     * 从 endpoint URL 提取区域名。
     * @param endpoint 对象存储 endpoint（如 https://oss-cn-hangzhou.aliyuncs.com）
     * @return 提取的区域名（如 cn-hangzhou）；无法识别返回 null
     */
    private String extractRegionFromEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String host = endpoint;
        // 去除协议头
        int schemeIdx = host.indexOf("://");
        if (schemeIdx >= 0) {
            host = host.substring(schemeIdx + 3);
        }
        // 去除端口和路径
        int portIdx = host.indexOf(':');
        if (portIdx >= 0) {
            host = host.substring(0, portIdx);
        }
        int pathIdx = host.indexOf('/');
        if (pathIdx >= 0) {
            host = host.substring(0, pathIdx);
        }

        // 阿里云 OSS: oss-cn-hangzhou.aliyuncs.com → cn-hangzhou
        if (host.startsWith("oss-") && host.contains(".aliyuncs.com")) {
            return host.substring(4, host.indexOf(".aliyuncs.com"));
        }
        // 华为云 OBS: obs.cn-north-1.myhuaweicloud.com → cn-north-1
        if (host.startsWith("obs.") && host.contains(".myhuaweicloud.com")) {
            return host.substring(4, host.indexOf(".myhuaweicloud.com"));
        }
        // AWS S3: s3.us-east-1.amazonaws.com → us-east-1
        if (host.startsWith("s3.") && host.contains(".amazonaws.com")) {
            return host.substring(3, host.indexOf(".amazonaws.com"));
        }
        // AWS S3 旧格式: s3-us-east-1.amazonaws.com → us-east-1
        if (host.startsWith("s3-") && host.contains(".amazonaws.com")) {
            return host.substring(3, host.indexOf(".amazonaws.com"));
        }
        return null;
    }

    /**
     * 确保 endpoint 包含协议头（http:// 或 https://）。
     * <p>部分平台返回的 endpoint 可能不含 scheme，S3 SDK 要求 URI 包含 scheme。</p>
     */
    private String ensureScheme(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "https://s3.amazonaws.com";
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            return "https://" + endpoint;
        }
        return endpoint;
    }
}
