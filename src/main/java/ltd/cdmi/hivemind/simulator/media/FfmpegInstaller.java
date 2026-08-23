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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * FFmpeg 一键安装服务（环境感知）。
 * <p>Windows：执行 {@code winget install ffmpeg} 安装，安装后自动查找 ffmpeg.exe 路径。
 * <p>非 Windows（Linux/macOS/Docker）：不执行 winget，通过 {@code which ffmpeg} 探测系统已有的 ffmpeg
 * （Docker 镜像已内置），探测到则直接返回路径供前端自动配置；探测不到则引导使用系统包管理器安装。
 */
@Component
public class FfmpegInstaller {

    private static final Logger log = LoggerFactory.getLogger(FfmpegInstaller.class);

    /** winget 安装超时（秒）：下载+安装可能较慢 */
    private static final int WINGET_TIMEOUT_SECONDS = 300;

    /** which ffmpeg 探测超时（秒） */
    private static final int WHICH_TIMEOUT_SECONDS = 5;

    /**
     * 执行 winget 安装 ffmpeg（无进度回调）。
     * @see #installFfmpeg(Consumer)
     */
    public Map<String, Object> installFfmpeg() {
        return installFfmpeg(line -> {});
    }

    /**
     * 一键安装/探测 ffmpeg，逐行推送进度。
     * <p>安装后自动查找 ffmpeg.exe 路径（WinGet Links 目录或 Packages 目录）。
     * @param progressCallback 每读取一行输出时回调，用于 SSE 实时推送
     * @return 安装结果：
     *   <ul>
     *     <li>success=true: 安装成功，含 ffmpegPath</li>
     *     <li>success=false: 安装失败，含 error 和 suggestion</li>
     *   </ul>
     */
    public Map<String, Object> installFfmpeg(Consumer<String> progressCallback) {
        return installFfmpeg(progressCallback, System.getProperty("os.name"), FfmpegInstaller::whichFfmpeg);
    }

    /**
     * 一键安装/探测 ffmpeg（环境可注入，供单元测试）。
     * @param osName 操作系统名（如 "Windows 11"、"Linux"）
     * @param whichResolver 非 Windows 环境下探测 ffmpeg 路径的函数，入参固定为 "ffmpeg"，返回绝对路径或 null
     */
    Map<String, Object> installFfmpeg(Consumer<String> progressCallback, String osName,
                                      java.util.function.UnaryOperator<String> whichResolver) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 0. 非 Windows 环境不走 winget：探测系统已有 ffmpeg（Docker 镜像内置），探测不到给出对应平台的安装指引
        if (!isWindowsOs(osName)) {
            return installOnUnix(progressCallback, whichResolver);
        }

        // 1. 检查 winget 是否可用
        progressCallback.accept("正在检查 winget 是否可用...");
        if (!isWingetAvailable()) {
            log.warn("winget 不可用，无法自动安装 ffmpeg");
            result.put("success", false);
            result.put("error", "winget 不可用");
            result.put("suggestion", "手动下载：访问 https://ffmpeg.org/download.html 下载 Windows 预编译版，解压后填写 ffmpeg.exe 完整路径");
            return result;
        }

        // 2. 执行 winget install ffmpeg
        progressCallback.accept("正在通过 winget 安装 ffmpeg（可能弹出 UAC 确认窗口）...");
        log.info("开始通过 winget 安装 ffmpeg（可能弹出 UAC 确认窗口）...");
        try {
            ProcessBuilder pb = new ProcessBuilder("winget", "install", "ffmpeg", "--accept-package-agreements", "--accept-source-agreements");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 消费输出（避免缓冲区满阻塞），逐行推送给前端
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("winget: {}", line);
                    if (!line.isBlank()) {
                        progressCallback.accept(line);
                    }
                }
            }

            boolean finished = process.waitFor(WINGET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("winget 安装超时（{}s）", WINGET_TIMEOUT_SECONDS);
                result.put("success", false);
                result.put("error", "安装超时（" + WINGET_TIMEOUT_SECONDS + "秒）");
                result.put("suggestion", "可能是网络下载缓慢，请稍后重试或手动下载");
                return result;
            }

            int exitCode = process.exitValue();
            String outputStr = output.toString();

            // winget exit code: 0=成功, -1978335189=已安装(已存在)
            if (exitCode != 0 && exitCode != -1978335189) {
                log.warn("winget 安装失败: exitCode={}, output={}", exitCode, outputStr);
                result.put("success", false);
                result.put("error", "winget 安装失败 (exitCode=" + exitCode + ")");
                if (outputStr.contains("UAC") || outputStr.contains("管理员") || outputStr.contains("elevation")) {
                    result.put("suggestion", "需要管理员权限，请以管理员身份运行模拟器后重试，或手动下载安装");
                } else if (outputStr.contains("network") || outputStr.contains("网络")) {
                    result.put("suggestion", "网络下载失败，请检查网络后重试或手动下载");
                } else {
                    result.put("suggestion", "手动下载：访问 https://ffmpeg.org/download.html");
                }
                return result;
            }

            log.info("winget 安装 ffmpeg 完成: exitCode={}", exitCode);
            progressCallback.accept("安装完成，正在查找 ffmpeg.exe 路径...");

            // 3. 查找 ffmpeg.exe 路径
            String ffmpegPath = findFfmpegPath();
            if (ffmpegPath != null) {
                log.info("已找到 ffmpeg: {}", ffmpegPath);
                result.put("success", true);
                result.put("ffmpegPath", ffmpegPath);
                result.put("message", "FFmpeg 安装成功");
                return result;
            }

            // 4. 安装成功但未找到路径（PATH 未刷新）
            log.warn("winget 安装成功但未找到 ffmpeg.exe 路径，可能需要重启模拟器");
            result.put("success", false);
            result.put("error", "安装成功但未找到 ffmpeg.exe 路径");
            result.put("suggestion", "请重启模拟器后重新检测，或手动填写 ffmpeg.exe 路径（通常在 C:\\Users\\{用户名}\\AppData\\Local\\Microsoft\\WinGet\\Links\\ffmpeg.exe）");
            return result;

        } catch (IOException e) {
            log.error("执行 winget 失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", "执行 winget 失败: " + e.getMessage());
            result.put("suggestion", "手动下载：访问 https://ffmpeg.org/download.html");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("winget 安装被中断: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", "安装被中断");
            result.put("suggestion", "请重试");
            return result;
        }
    }

    /**
     * 非 Windows 环境的处理：探测系统已有 ffmpeg（Docker 镜像内置 /usr/bin/ffmpeg），
     * 探测到直接返回路径（前端自动填入并启用真实推流）；探测不到引导使用系统包管理器安装。
     */
    private Map<String, Object> installOnUnix(Consumer<String> progressCallback,
                                              java.util.function.UnaryOperator<String> whichResolver) {
        Map<String, Object> result = new LinkedHashMap<>();
        progressCallback.accept("非 Windows 环境，正在探测系统已安装的 ffmpeg...");
        String path = whichResolver.apply("ffmpeg");
        if (path != null && !path.isBlank()) {
            log.info("非 Windows 环境探测到系统 ffmpeg: {}", path);
            progressCallback.accept("已检测到系统 ffmpeg: " + path);
            result.put("success", true);
            result.put("ffmpegPath", path.trim());
            result.put("message", "已检测到系统 ffmpeg（" + path.trim() + "），无需安装");
            return result;
        }
        log.warn("非 Windows 环境未找到 ffmpeg");
        result.put("success", false);
        result.put("error", "非 Windows 环境不支持 winget 一键安装，且未探测到系统 ffmpeg");
        result.put("suggestion", "请使用系统包管理器安装：apt-get install ffmpeg（Ubuntu/Debian）或 apk add ffmpeg（Alpine）。Docker 部署的官方镜像已内置 ffmpeg，正常无需安装");
        return result;
    }

    /**
     * 通过 which 命令探测 ffmpeg 绝对路径（Linux/macOS）。
     * @return 绝对路径；未安装或 which 不可用时返回 null
     */
    static String whichFfmpeg(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(WHICH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return (line != null && !line.isBlank()) ? line.trim() : null;
            }
        } catch (Exception e) {
            log.debug("which 探测失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 当前运行环境是否为 Windows。
     */
    public static boolean isWindowsOs() {
        return isWindowsOs(System.getProperty("os.name"));
    }

    /**
     * 判断操作系统名是否为 Windows（供测试注入 os.name）。
     */
    static boolean isWindowsOs(String osName) {
        return osName != null && osName.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * 检查 winget 是否可用。
     */
    private boolean isWingetAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("winget", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("winget 不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 查找 ffmpeg.exe 路径。
     * <p>查找顺序：
     * <ol>
     *   <li>WinGet Links 目录（符号链接）</li>
     *   <li>WinGet Packages 目录（实际安装路径）</li>
     *   <li>常见安装路径</li>
     * </ol>
     */
    private String findFfmpegPath() {
        String userHome = System.getProperty("user.home");
        List<Path> searchPaths = new ArrayList<>();

        // 1. WinGet Links 目录（最常见）
        searchPaths.add(Path.of(userHome, "AppData", "Local", "Microsoft", "WinGet", "Links", "ffmpeg.exe"));

        // 2. WinGet Packages 目录（递归查找）
        Path wingetPackages = Path.of(userHome, "AppData", "Local", "Microsoft", "WinGet", "Packages");
        if (Files.isDirectory(wingetPackages)) {
            try (Stream<Path> paths = Files.walk(wingetPackages, 4)) {
                paths.filter(p -> p.getFileName().toString().equals("ffmpeg.exe"))
                     .filter(Files::isExecutable)
                     .forEach(searchPaths::add);
            } catch (IOException e) {
                log.debug("遍历 WinGet Packages 失败: {}", e.getMessage());
            }
        }

        // 3. 常见安装路径
        searchPaths.add(Path.of("C:\\ffmpeg\\bin\\ffmpeg.exe"));
        searchPaths.add(Path.of("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe"));

        for (Path p : searchPaths) {
            if (Files.isExecutable(p)) {
                return p.toString();
            }
        }

        return null;
    }
}
