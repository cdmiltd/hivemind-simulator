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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 媒体示例文件预置器。
 * <p>设计背景（TDD-SPEC TC-MEDIA-016~018）：航线任务完成（落地）即异步触发媒体上传，
 * 无运行时补传机会；Docker 新部署 media-dir 为空目录会导致落地后无真实文件可上传
 * （仅虚构元数据）。因此在 media-dir 已配置且无媒体文件时，从 JAR 内置资源解出
 * 小体积示例照片+视频（合计约 30KB，S3 上传秒级完成），保证 STS→S3→callback
 * 全链路开箱可验证。
 * <p>触发时机：应用启动（media-dir 已有持久化配置时）、前端保存直播/媒体配置后
 * （{@code POST /api/live/config}，目录首次配置场景）。
 * <p>预置不污染用户文件：目录中已有受支持的媒体文件时不做任何写入；
 * media-dir 未配置时保持既有降级行为（TC-MEDIA-012 仅元数据上报）。
 */
@Component
public class MediaSampleInitializer {

    private static final Logger log = LoggerFactory.getLogger(MediaSampleInitializer.class);

    /** JAR 内置示例资源（src/main/resources/media-samples/）与解出后的文件名 */
    private static final String[][] SAMPLE_RESOURCES = {
            {"media-samples/sample-photo.jpg", "sample-photo.jpg"},
            {"media-samples/sample-video.mp4", "sample-video.mp4"},
    };

    private final RuntimeConfig runtimeConfig;
    private final MediaUploader mediaUploader;

    public MediaSampleInitializer(RuntimeConfig runtimeConfig, MediaUploader mediaUploader) {
        this.runtimeConfig = runtimeConfig;
        this.mediaUploader = mediaUploader;
    }

    /**
     * 应用启动时检查并预置（media-dir 已配置的场景）。
     * <p>预置失败仅记录 warn，不影响应用启动。
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        ensurePresetMediaFiles();
    }

    /**
     * 确保 media-dir 中有可上传的媒体文件：目录已配置且无任何受支持媒体文件时，
     * 从 classpath 解出内置示例照片+视频；已有用户文件时不做任何写入。
     */
    public void ensurePresetMediaFiles() {
        String mediaDir = runtimeConfig.getMediaDir();
        if (mediaDir == null || mediaDir.isBlank()) {
            return; // 未配置，保持 TC-MEDIA-012 降级行为，不预置
        }
        try {
            Path dir = Path.of(mediaDir);
            Files.createDirectories(dir);
            if (!mediaUploader.listMediaFiles(mediaDir).isEmpty()) {
                log.debug("media-dir 已有媒体文件，跳过示例预置: {}", mediaDir);
                return;
            }
            for (String[] resource : SAMPLE_RESOURCES) {
                extract(resource[0], dir.resolve(resource[1]));
            }
            log.info("media-dir 无媒体文件，已预置示例照片/视频（模拟拍照+录像素材）: {}", mediaDir);
        } catch (Exception e) {
            log.warn("预置示例媒体文件失败（不影响应用运行）: {} - {}", mediaDir, e.getMessage());
        }
    }

    /**
     * 从 classpath 解出单个内置资源到目标路径（已存在时跳过，幂等）。
     */
    private void extract(String classpath, Path target) throws Exception {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("已预置示例媒体文件: {}", target);
    }
}
