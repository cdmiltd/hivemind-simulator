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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FfmpegInstaller 单元测试。
 * <p>覆盖 TDD-SPEC.md 2.19 直播功能 TC-LIVE-016/017（一键安装 FFmpeg 环境感知）：
 * <ul>
 *   <li>非 Windows 且系统已有 ffmpeg（如 Docker 镜像内置）→ 探测成功，返回路径</li>
 *   <li>非 Windows 且系统无 ffmpeg → 引导系统包管理器，不出现 winget/ffmpeg.org Windows 指引</li>
 *   <li>os.name 环境判断</li>
 * </ul>
 * 注：Windows 分支会真实调用 winget，不在单元测试中执行。
 */
class FfmpegInstallerTest {

    private final FfmpegInstaller installer = new FfmpegInstaller();

    private final List<String> progressLines = new ArrayList<>();

    private Map<String, Object> install(String osName, UnaryOperator<String> whichResolver) {
        return installer.installFfmpeg(progressLines::add, osName, whichResolver);
    }

    @Test
    @DisplayName("TC-LIVE-016: 非 Windows 且系统已有 ffmpeg（Docker 内置）→ 探测成功返回绝对路径")
    void unixWithFfmpegDetected() {
        Map<String, Object> result = install("Linux", cmd -> "/usr/bin/ffmpeg");

        assertEquals(Boolean.TRUE, result.get("success"), "Docker 内置 ffmpeg 应探测成功");
        assertEquals("/usr/bin/ffmpeg", result.get("ffmpegPath"));
        assertTrue(((String) result.get("message")).contains("无需安装"));
        // 进度输出应包含探测提示（供 SSE 前端展示）
        assertTrue(progressLines.stream().anyMatch(l -> l.contains("探测")), "应有探测进度输出");
    }

    @Test
    @DisplayName("TC-LIVE-017: 非 Windows 且系统无 ffmpeg → 引导包管理器，禁止 Windows 专属指引")
    void unixWithoutFfmpegSuggestsPackageManager() {
        Map<String, Object> result = install("Linux", cmd -> null);

        assertEquals(Boolean.FALSE, result.get("success"));
        String error = (String) result.get("error");
        String suggestion = (String) result.get("suggestion");
        assertNotNull(error);
        assertNotNull(suggestion);
        assertTrue(error.contains("非 Windows"), "错误应说明非 Windows 环境不支持 winget");
        assertTrue(suggestion.contains("apt-get install ffmpeg"), "应引导系统包管理器安装");
        // 回归断言：不得出现 Windows 专属指引（原 Bug：Docker 部署弹 winget/ffmpeg.org Windows 下载提示）
        assertFalse(suggestion.contains("winget"), "suggestion 不得含 winget");
        assertFalse(suggestion.contains("ffmpeg.org"), "suggestion 不得含 ffmpeg.org Windows 下载指引");
        assertFalse(suggestion.contains("预编译"), "suggestion 不得引导 Windows 预编译版");
    }

    @Test
    @DisplayName("macOS 环境同样走探测分支")
    void macOsUsesDetectionBranch() {
        Map<String, Object> result = install("Mac OS X", cmd -> "/opt/homebrew/bin/ffmpeg");

        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("/opt/homebrew/bin/ffmpeg", result.get("ffmpegPath"));
    }

    @Test
    @DisplayName("os.name 环境判断：Windows 系列识别为 Windows，Linux/macOS/null 识别为非 Windows")
    void osNameClassification() {
        assertTrue(FfmpegInstaller.isWindowsOs("Windows 11"));
        assertTrue(FfmpegInstaller.isWindowsOs("Windows Server 2022"));
        assertTrue(FfmpegInstaller.isWindowsOs("windows 10")); // 大小写不敏感
        assertFalse(FfmpegInstaller.isWindowsOs("Linux"));
        assertFalse(FfmpegInstaller.isWindowsOs("Mac OS X"));
        assertFalse(FfmpegInstaller.isWindowsOs(null));
    }
}
