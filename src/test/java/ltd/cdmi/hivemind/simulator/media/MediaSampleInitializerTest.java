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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaSampleInitializer 单元测试。
 * <p>覆盖 TDD-SPEC.md 2.20 媒体管理 TC-MEDIA-016/017（media-dir 预置示例照片/视频）：
 * 任务完成（落地）即异步触发媒体上传，无运行时补传机会，空目录会导致落地后无真实文件可上传。
 */
class MediaSampleInitializerTest {

    @TempDir
    Path tempDir;

    private MediaSampleInitializer createInitializer(String mediaDir) {
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
        runtimeConfig.setMediaDir(mediaDir);
        return new MediaSampleInitializer(runtimeConfig, new MediaUploader());
    }

    // ==================== TC-MEDIA-016：空目录自动预置 ====================

    @Test
    @DisplayName("TC-MEDIA-016: media-dir 不存在时自动创建并预置示例照片+视频")
    void presetsSamplesIntoMissingDirectory() {
        Path dir = tempDir.resolve("media");
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.ensurePresetMediaFiles();

        assertTrue(Files.exists(dir.resolve("sample-photo.jpg")), "示例照片应被预置");
        assertTrue(Files.exists(dir.resolve("sample-video.mp4")), "示例视频应被预置");
        // 预置文件可被媒体上传扫描到（扩展名在支持列表内）
        List<Path> files = new MediaUploader().listMediaFiles(dir.toString());
        assertEquals(2, files.size(), "listMediaFiles 应扫描到 2 个示例文件");
    }

    @Test
    @DisplayName("TC-MEDIA-016: 目录存在但为空时同样预置，且预置体积小（<200KB）")
    void presetsIntoEmptyDirectoryWithSmallSize() {
        Path dir = tempDir;
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.ensurePresetMediaFiles();

        try {
            long photoSize = Files.size(dir.resolve("sample-photo.jpg"));
            long videoSize = Files.size(dir.resolve("sample-video.mp4"));
            assertTrue(photoSize > 0 && photoSize < 200 * 1024, "照片体积应在 (0, 200KB)，实际 " + photoSize);
            assertTrue(videoSize > 0 && videoSize < 200 * 1024, "视频体积应在 (0, 200KB)，实际 " + videoSize);
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("TC-MEDIA-016: 重复调用幂等，不重复写入不报错")
    void idempotentOnRepeatedCalls() {
        Path dir = tempDir.resolve("media");
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.ensurePresetMediaFiles();
        initializer.ensurePresetMediaFiles();

        List<Path> files = new MediaUploader().listMediaFiles(dir.toString());
        assertEquals(2, files.size(), "重复调用不应产生额外文件");
    }

    // ==================== TC-MEDIA-017：不污染用户文件/未配置不预置 ====================

    @Test
    @DisplayName("TC-MEDIA-017: 目录已有用户媒体文件时不预置（不污染用户文件集）")
    void skipsWhenUserFilesPresent() {
        Path dir = tempDir.resolve("media");
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("myphoto.jpg"), new byte[]{1, 2, 3});
        } catch (Exception e) {
            fail(e);
        }
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.ensurePresetMediaFiles();

        assertFalse(Files.exists(dir.resolve("sample-photo.jpg")), "不应预置示例照片");
        assertFalse(Files.exists(dir.resolve("sample-video.mp4")), "不应预置示例视频");
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "目录应只含用户自己的文件");
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("TC-MEDIA-017: media-dir 未配置时不预置、不创建目录、不报错")
    void noOpWhenMediaDirBlank() {
        Path dir = tempDir.resolve("should-not-exist");
        MediaSampleInitializer initializer = createInitializer("");

        initializer.ensurePresetMediaFiles();

        assertFalse(Files.exists(dir), "未配置时不应创建目录");
        assertFalse(Files.exists(tempDir.resolve("sample-photo.jpg")), "未配置时不应解出文件");
    }

    @Test
    @DisplayName("init()（应用启动钩子）与 ensurePresetMediaFiles 行为一致")
    void initBehavesSameAsEnsure() {
        Path dir = tempDir.resolve("media");
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.init();

        assertTrue(Files.exists(dir.resolve("sample-photo.jpg")));
        assertTrue(Files.exists(dir.resolve("sample-video.mp4")));
    }
}
