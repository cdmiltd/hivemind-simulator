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

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地素材文件上传服务：把前端上传的文件保存到配置目录。
 * <p>两类目录（TDD-SPEC TC-LIVE-019/020、TC-MEDIA-019/020）：
 * <ul>
 *   <li>{@link #uploadLiveVideo}：直播推流视频目录（video-dir），白名单 mp4/flv/mkv（与推流扫描规则一致）</li>
 *   <li>{@link #uploadMedia}：媒体上传素材目录（media-dir），白名单与 {@link MediaUploader#SUPPORTED_EXTENSIONS}
 *       一致（jpg/jpeg/png/dng/tif/tiff/mp4/mov/avi），上传后进入媒体上传素材池</li>
 * </ul>
 * <p>设计背景：本地部署可将文件直接放入目录；Docker 部署中两目录均为 named volume，
 * 宿主机文件需 docker cp 才能进入，通过 Web UI 上传替代。
 * <p>安全底线：文件名净化为纯文件名（仅字母数字、点、下划线、连字符），防止目录穿越；
 * 扩展名白名单防止任意文件写入。失败返回 HTTP 200 + success=false（不抛异常）。
 * <p>命名约定（前端一致）：「上传」= 本地文件传入目录（本服务）；「上报」= 协议上报云端（MediaUploadSimulator）。
 */
@Service
public class LocalFileUploadService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileUploadService.class);

    /** 直播推流视频扩展名（与 FfmpegWhipPusher.listVideoFiles 扫描过滤一致，避免上传后扫描不到） */
    static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".flv", ".mkv");

    /** 媒体上传素材扩展名（与 MediaUploader.SUPPORTED_EXTENSIONS 一致，进入媒体上传素材池） */
    static final Set<String> MEDIA_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".dng", ".tif", ".tiff", ".mp4", ".mov", ".avi");

    private final RuntimeConfig runtimeConfig;
    private final MediaUploader mediaUploader;

    public LocalFileUploadService(RuntimeConfig runtimeConfig, MediaUploader mediaUploader) {
        this.runtimeConfig = runtimeConfig;
        this.mediaUploader = mediaUploader;
    }

    /**
     * 上传直播推流视频文件到 video-dir（multipart，字段名 file）。
     * @return success=true 时含 filename/size；失败时含 message 说明原因
     */
    public Map<String, Object> uploadLiveVideo(MultipartFile file) {
        return upload(file, runtimeConfig.getLiveVideoDir(), VIDEO_EXTENSIONS, "直播推流视频");
    }

    /**
     * 上传媒体素材文件到 media-dir（multipart，字段名 file）。
     * <p>文件进入媒体上传素材池，任务完成触发媒体上传时循环取用（TC-MEDIA-011）。
     * @return success=true 时含 filename/size/mediaFiles（目录内全部媒体文件名，前端免二次请求）
     */
    public Map<String, Object> uploadMedia(MultipartFile file) {
        Map<String, Object> result = upload(file, runtimeConfig.getMediaDir(), MEDIA_EXTENSIONS, "媒体素材");
        if (Boolean.TRUE.equals(result.get("success"))) {
            result.put("mediaFiles", listMediaFileNames());
        }
        return result;
    }

    /**
     * 通用上传：校验 → 净化文件名 → 创建目录 → 落盘。
     * @param dir          目标目录（来自运行时配置）
     * @param allowedExts  允许的扩展名集合
     * @param label        业务标签（用于提示信息与日志）
     */
    private Map<String, Object> upload(MultipartFile file, String dir, Set<String> allowedExts, String label) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (file == null || file.isEmpty()) {
            result.put("success", false);
            result.put("message", "上传文件为空");
            return result;
        }
        if (dir == null || dir.isBlank()) {
            result.put("success", false);
            result.put("message", label + "目录未配置，请先在上方填写目录并保存");
            return result;
        }
        String name = sanitizeFilename(file.getOriginalFilename());
        if (name == null || !isAllowedExtension(name, allowedExts)) {
            result.put("success", false);
            result.put("message", "仅支持 " + joinExts(allowedExts) + " 格式的" + label + "文件");
            return result;
        }
        try {
            Path target = Path.of(dir).toAbsolutePath().normalize();
            Files.createDirectories(target);
            target = target.resolve(name);
            file.transferTo(target);
            log.info("{}上传成功: {} ({} bytes)", label, target, Files.size(target));
            result.put("success", true);
            result.put("filename", name);
            result.put("size", Files.size(target));
            return result;
        } catch (IOException e) {
            log.error("{}保存失败: {} - {}", label, dir, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "文件保存失败: " + e.getMessage());
            return result;
        }
    }

    /** 列出 media-dir 内全部媒体文件名（供上传响应携带与配置弹窗展示目录内容）。 */
    public List<String> listMediaFileNames() {
        return mediaUploader.listMediaFiles(runtimeConfig.getMediaDir()).stream()
                .map(p -> p.getFileName().toString())
                .toList();
    }

    /**
     * 净化上传文件名：去除任何路径成分（兼容 / 与 \ 分隔），仅允许字母数字、点、下划线、连字符。
     * @return 安全的纯文件名；无法净化（含非法字符或空名）返回 null
     */
    static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return null;
        }
        String name = original.replace('\\', '/');
        int idx = name.lastIndexOf('/');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        if (!name.matches("[A-Za-z0-9._-]+") || name.startsWith(".") || name.isBlank()) {
            return null;
        }
        return name;
    }

    /**
     * 扩展名是否在允许列表内（不区分大小写）。
     */
    static boolean isAllowedExtension(String filename, Set<String> allowedExts) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return allowedExts.stream().anyMatch(lower::endsWith);
    }

    /** 扩展名集合拼接为提示文本（如 "mp4 / flv / mkv"）。 */
    private static String joinExts(Set<String> exts) {
        return exts.stream().map(e -> e.substring(1)).sorted().reduce((a, b) -> a + " / " + b).orElse("");
    }
}
