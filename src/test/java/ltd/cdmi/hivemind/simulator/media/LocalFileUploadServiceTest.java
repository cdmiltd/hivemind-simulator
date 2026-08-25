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

import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LocalFileUploadService 单元测试。
 * <p>覆盖 TDD-SPEC.md 直播功能 TC-LIVE-019/020（直播视频文件上传）与
 * 媒体管理 TC-MEDIA-019/020（媒体素材文件上传），均为 Docker 部署场景的
 * 本地文件管理（文件传入配置目录，与协议上报无关）。
 */
class LocalFileUploadServiceTest {

    @TempDir
    Path tempDir;

    private final MediaUploader mediaUploader = new MediaUploader();

    private LocalFileUploadService createService(String videoDir, String mediaDir) {
        SimulatorProperties props = new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null, null
        );
        RuntimeConfig runtimeConfig = new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                props, new LiveConfigStore());
        runtimeConfig.setLiveVideoDir(videoDir);
        runtimeConfig.setMediaDir(mediaDir);
        return new LocalFileUploadService(runtimeConfig, mediaUploader);
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content.getBytes());
    }

    // ==================== TC-LIVE-019：直播视频上传（原有行为，重构后保持） ====================

    @Test
    @DisplayName("TC-LIVE-019: video-dir 已配置时上传 default.mp4 保存成功且保留原名")
    void uploadLiveVideoSavesToVideoDir() {
        Path dir = tempDir.resolve("videos");
        LocalFileUploadService service = createService(dir.toString(), "");

        Map<String, Object> result = service.uploadLiveVideo(file("default.mp4", "fake-video-content"));

        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("default.mp4", result.get("filename"));
        assertTrue(Files.exists(dir.resolve("default.mp4")), "文件应保存到 video-dir 下");
        assertEquals((long) "fake-video-content".length(), ((Number) result.get("size")).longValue());
    }

    @Test
    @DisplayName("TC-LIVE-019: 相机命名文件 {camera_index}-{video_type}.mp4 同样支持")
    void uploadLiveVideoCameraNamedFile() {
        LocalFileUploadService service = createService(tempDir.toString(), "");

        Map<String, Object> result = service.uploadLiveVideo(file("165-0-7-normal.mp4", "video"));

        assertEquals(Boolean.TRUE, result.get("success"), "相机命名文件应上传成功");
        assertEquals("165-0-7-normal.mp4", result.get("filename"));
        assertTrue(Files.exists(tempDir.resolve("165-0-7-normal.mp4")));
    }

    // ==================== TC-LIVE-020：直播视频校验（原有行为，重构后保持） ====================

    @Test
    @DisplayName("TC-LIVE-020: video-dir 未配置时拒绝上传，无文件写入")
    void uploadLiveVideoRejectsWhenVideoDirEmpty() {
        LocalFileUploadService service = createService("", "");

        Map<String, Object> result = service.uploadLiveVideo(file("default.mp4", "video"));

        assertEquals(Boolean.FALSE, result.get("success"));
        assertTrue(((String) result.get("message")).contains("目录未配置"));
    }

    @Test
    @DisplayName("TC-LIVE-020: video-dir 指向不存在的目录时自动创建后保存")
    void uploadLiveVideoCreatesMissingDirectory() {
        Path dir = tempDir.resolve("a").resolve("b").resolve("videos");
        LocalFileUploadService service = createService(dir.toString(), "");

        Map<String, Object> result = service.uploadLiveVideo(file("default.mp4", "video"));

        assertEquals(Boolean.TRUE, result.get("success"));
        assertTrue(Files.exists(dir.resolve("default.mp4")), "目录应被自动创建且文件保存成功");
    }

    @Test
    @DisplayName("TC-LIVE-020: 非视频扩展名（exe/txt）被拒绝且无文件产生")
    void uploadLiveVideoRejectsDisallowedExtensions() {
        LocalFileUploadService service = createService(tempDir.toString(), "");

        Map<String, Object> exe = service.uploadLiveVideo(file("evil.exe", "x"));
        Map<String, Object> txt = service.uploadLiveVideo(file("note.txt", "x"));

        assertEquals(Boolean.FALSE, exe.get("success"));
        assertEquals(Boolean.FALSE, txt.get("success"));
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count(), "不应产生任何文件");
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("TC-LIVE-020: 文件名含路径成分时净化为纯文件名，不产生目录穿越")
    void uploadLiveVideoSanitizesPathTraversalFilenames() {
        LocalFileUploadService service = createService(tempDir.toString(), "");

        Map<String, Object> result = service.uploadLiveVideo(file("../evil.mp4", "video"));

        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("evil.mp4", result.get("filename"));
        assertFalse(Files.exists(tempDir.getParent().resolve("evil.mp4")), "父目录不应出现 evil.mp4（无目录穿越）");
        assertTrue(Files.exists(tempDir.resolve("evil.mp4")), "净化后的文件应保存在 video-dir 内");
    }

    @Test
    @DisplayName("TC-LIVE-020: Windows 风格路径分隔符 a\\b.mp4 同样净化为 b.mp4")
    void uploadLiveVideoSanitizesWindowsBackslashFilenames() {
        LocalFileUploadService service = createService(tempDir.toString(), "");

        Map<String, Object> result = service.uploadLiveVideo(file("a\\b.mp4", "video"));

        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("b.mp4", result.get("filename"));
        assertTrue(Files.exists(tempDir.resolve("b.mp4")));
    }

    @Test
    @DisplayName("含空文件名/纯路径的文件被拒绝")
    void rejectsBlankFilename() {
        LocalFileUploadService service = createService(tempDir.toString(), "");

        assertEquals(Boolean.FALSE, service.uploadLiveVideo(file("", "video")).get("success"));
    }

    @Test
    @DisplayName("空文件被拒绝")
    void rejectsEmptyFile() {
        LocalFileUploadService service = createService(tempDir.toString(), "");

        Map<String, Object> result = service.uploadLiveVideo(file("default.mp4", ""));

        assertEquals(Boolean.FALSE, result.get("success"));
        assertTrue(((String) result.get("message")).contains("为空"));
    }

    @Test
    @DisplayName("直播视频扩展名校验大小写不敏感")
    void uploadLiveVideoExtensionCaseInsensitive() {
        LocalFileUploadService service = createService(tempDir.toString(), "");

        assertEquals(Boolean.TRUE, service.uploadLiveVideo(file("DEFAULT.MP4", "video")).get("success"));
    }

    // ==================== TC-MEDIA-019：媒体素材上传（新增） ====================

    @Test
    @DisplayName("TC-MEDIA-019: media-dir 已配置时上传 myphoto.jpg 保存成功且保留原名")
    void uploadMediaSavesToMediaDir() {
        Path dir = tempDir.resolve("media");
        LocalFileUploadService service = createService("", dir.toString());

        Map<String, Object> result = service.uploadMedia(file("myphoto.jpg", "fake-photo"));

        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("myphoto.jpg", result.get("filename"));
        assertTrue(Files.exists(dir.resolve("myphoto.jpg")), "文件应保存到 media-dir 下");
    }

    @Test
    @DisplayName("TC-MEDIA-019: 成功响应含 mediaFiles 数组（目录内全部媒体文件名，含预置示例）")
    void uploadMediaReturnsMediaFileList() {
        Path dir = tempDir.resolve("media");
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("sample-photo.jpg"), new byte[]{1});
        } catch (Exception e) {
            fail(e);
        }
        LocalFileUploadService service = createService("", dir.toString());

        Map<String, Object> result = service.uploadMedia(file("myphoto.jpg", "fake-photo"));

        assertEquals(Boolean.TRUE, result.get("success"));
        @SuppressWarnings("unchecked")
        List<String> mediaFiles = (List<String>) result.get("mediaFiles");
        assertNotNull(mediaFiles, "响应应含 mediaFiles 数组");
        assertTrue(mediaFiles.contains("myphoto.jpg"), "mediaFiles 应包含新上传文件");
        assertTrue(mediaFiles.contains("sample-photo.jpg"), "mediaFiles 应包含目录内已有文件");
    }

    @Test
    @DisplayName("TC-MEDIA-019: 媒体扩展名全集支持（jpg/dng/mov 等进入媒体素材池）")
    void uploadMediaSupportsFullMediaExtensions() {
        LocalFileUploadService service = createService("", tempDir.toString());

        assertEquals(Boolean.TRUE, service.uploadMedia(file("a.dng", "x")).get("success"));
        assertEquals(Boolean.TRUE, service.uploadMedia(file("b.mov", "x")).get("success"));
        assertEquals(Boolean.TRUE, service.uploadMedia(file("c.PNG", "x")).get("success"));
        assertEquals(Boolean.TRUE, service.uploadMedia(file("d.TIFF", "x")).get("success"));
        assertEquals(Boolean.TRUE, service.uploadMedia(file("e.avi", "x")).get("success"));
    }

    @Test
    @DisplayName("TC-MEDIA-019: 上传的媒体文件可被 listMediaFiles 扫描（进入素材池）")
    void uploadedMediaFileIsScannable() {
        LocalFileUploadService service = createService("", tempDir.toString());

        service.uploadMedia(file("myphoto.jpg", "fake-photo"));

        List<Path> scanned = mediaUploader.listMediaFiles(tempDir.toString());
        assertEquals(1, scanned.size());
        assertEquals("myphoto.jpg", scanned.get(0).getFileName().toString());
    }

    // ==================== TC-MEDIA-020：媒体上传校验（新增） ====================

    @Test
    @DisplayName("TC-MEDIA-020: media-dir 未配置时拒绝上传，无文件写入")
    void uploadMediaRejectsWhenMediaDirEmpty() {
        LocalFileUploadService service = createService("", "");

        Map<String, Object> result = service.uploadMedia(file("myphoto.jpg", "x"));

        assertEquals(Boolean.FALSE, result.get("success"));
        assertTrue(((String) result.get("message")).contains("目录未配置"));
    }

    @Test
    @DisplayName("TC-MEDIA-020: media-dir 指向不存在的目录时自动创建后保存")
    void uploadMediaCreatesMissingDirectory() {
        Path dir = tempDir.resolve("x").resolve("y").resolve("media");
        LocalFileUploadService service = createService("", dir.toString());

        Map<String, Object> result = service.uploadMedia(file("myphoto.jpg", "x"));

        assertEquals(Boolean.TRUE, result.get("success"));
        assertTrue(Files.exists(dir.resolve("myphoto.jpg")));
    }

    @Test
    @DisplayName("TC-MEDIA-020: 非媒体扩展名被拒绝（exe/txt，且 .flv 属直播格式不进媒体白名单）")
    void uploadMediaRejectsDisallowedExtensions() {
        LocalFileUploadService service = createService("", tempDir.toString());

        assertEquals(Boolean.FALSE, service.uploadMedia(file("evil.exe", "x")).get("success"));
        assertEquals(Boolean.FALSE, service.uploadMedia(file("note.txt", "x")).get("success"));
        assertEquals(Boolean.FALSE, service.uploadMedia(file("stream.flv", "x")).get("success"),
                ".flv 属直播推流格式，不在媒体素材白名单");
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count(), "不应产生任何文件");
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("TC-MEDIA-020: 媒体文件名含路径成分时净化，不产生目录穿越")
    void uploadMediaSanitizesPathTraversalFilenames() {
        LocalFileUploadService service = createService("", tempDir.toString());

        Map<String, Object> result = service.uploadMedia(file("../evil.jpg", "x"));

        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("evil.jpg", result.get("filename"));
        assertFalse(Files.exists(tempDir.getParent().resolve("evil.jpg")), "无目录穿越");
        assertTrue(Files.exists(tempDir.resolve("evil.jpg")));
    }

    // ==================== 净化/校验静态方法 ====================

    @Test
    @DisplayName("文件名净化：合法字符保留，非法字符拒绝")
    void sanitizeFilenameRules() {
        assertEquals("default.mp4", LocalFileUploadService.sanitizeFilename("default.mp4"));
        assertEquals("165-0-7_normal.mp4", LocalFileUploadService.sanitizeFilename("165-0-7_normal.mp4"));
        assertEquals("evil.mp4", LocalFileUploadService.sanitizeFilename("../../evil.mp4"));
        assertEquals("b.mp4", LocalFileUploadService.sanitizeFilename("a\\b.mp4"));
        assertNull(LocalFileUploadService.sanitizeFilename(null));
        assertNull(LocalFileUploadService.sanitizeFilename("  "));
        assertNull(LocalFileUploadService.sanitizeFilename("file name.mp4"), "空格不允许");
        assertNull(LocalFileUploadService.sanitizeFilename("文件名.mp4"), "非 ASCII 不允许");
        assertNull(LocalFileUploadService.sanitizeFilename(".mp4"), "点开头（无主名）不允许");
    }

    @Test
    @DisplayName("两类白名单互斥分开：flv 仅属视频，dng 仅属媒体")
    void whitelistSetsAreSeparate() {
        assertTrue(LocalFileUploadService.isAllowedExtension("a.flv", LocalFileUploadService.VIDEO_EXTENSIONS));
        assertFalse(LocalFileUploadService.isAllowedExtension("a.flv", LocalFileUploadService.MEDIA_EXTENSIONS));
        assertTrue(LocalFileUploadService.isAllowedExtension("a.dng", LocalFileUploadService.MEDIA_EXTENSIONS));
        assertFalse(LocalFileUploadService.isAllowedExtension("a.dng", LocalFileUploadService.VIDEO_EXTENSIONS));
    }
}
