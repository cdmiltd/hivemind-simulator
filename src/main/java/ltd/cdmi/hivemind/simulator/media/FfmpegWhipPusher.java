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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.ServiceMethod;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * FFmpeg WHIP 推流器：负责检测 ffmpeg 能力并管理推流进程。
 * <p>启动时检测本机 ffmpeg 是否支持 WHIP muxer（需 ffmpeg ≥ 8.0 且编译时启用 --enable-muxer=whip）。
 * <p>支持 RTMP (url_type=1) 和 WebRTC (url_type=4) 真实推流；Agora (url_type=0) 和 GB28181 (url_type=3) 不处理（降级为协议模拟）。
 * <p>配置来源：{@link RuntimeConfig}（yml 提供默认值，前端 REST API 可运行时覆盖，无需重启）。
 * <p>真相源：TDD-SPEC §2.18 TC-LIVE-011~015。
 * <p>核实依据：
 * <ul>
 *   <li><a href="https://live777.pages.dev/guide/ffmpeg">live777 FFmpeg WHIP 文档</a>：大多数预编译版不支持 WHIP</li>
 *   <li><a href="https://github.com/ossrs/ffmpeg-webrtc/discussions/47">ossrs/ffmpeg-webrtc</a>：推流命令参考</li>
 * </ul>
 */
@Component
public class FfmpegWhipPusher {

    private static final Logger log = LoggerFactory.getLogger(FfmpegWhipPusher.class);

    /** url_type=0 声网 Agora（不真实推流，仅协议模拟） */
    public static final int URL_TYPE_AGORA = 0;
    /** url_type=1 表示 RTMP */
    public static final int URL_TYPE_RTMP = 1;
    /** url_type=3 GB28181（不真实推流，仅协议模拟） */
    public static final int URL_TYPE_GB28181 = 3;
    /** url_type=4 表示 WebRTC (WHIP) */
    public static final int URL_TYPE_WEBRTC = 4;

    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;

    /** ffmpeg 是否支持 WHIP muxer（WebRTC 推流，需特殊编译） */
    private volatile boolean whipSupported = false;
    /** ffmpeg 是否支持 RTMP 推流（flv muxer + rtmp protocol，普通预编译版即支持） */
    private volatile boolean rtmpSupported = false;
    /** 检测是否已执行（避免重复检测） */
    private volatile boolean detected = false;

    /** ffmpeg 检测细分状态（供前端精确展示验证结果） */
    private volatile String ffmpegStatus = "NOT_CHECKED";
    /** ffmpeg 检测错误详情（失败时填具体原因，成功时为空） */
    private volatile String ffmpegError = "";

    /** 活跃推流进程表：videoId → Process */
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    public FfmpegWhipPusher(RuntimeConfig runtimeConfig, DiagnosticLogRecorder diagnosticRecorder) {
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
    }

    @PostConstruct
    public void detectCapabilities() {
        if (detected) {
            return;
        }
        detected = true;

        if (!runtimeConfig.isLiveRealPushEnabled()) {
            log.info("直播真实推流未启用 (real-push-enabled=false)，仅协议模拟");
            ffmpegStatus = "NOT_CHECKED";
            ffmpegError = "";
            return;
        }

        String ffmpegPath = runtimeConfig.getLiveFfmpegPath();
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            log.warn("ffmpeg-path 未配置，无法启用真实推流");
            ffmpegStatus = "PATH_EMPTY";
            ffmpegError = "FFmpeg 路径未配置";
            diagnosticRecorder.record(DiagnosticCode.SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED, "-",
                    "ffmpeg-path 未配置，真实推流不可用");
            return;
        }

        try {
            // 一次执行 ffmpeg -muxers，同时检测 whip 和 flv
            boolean[] muxers = checkMuxers(ffmpegPath);
            whipSupported = muxers[0];
            rtmpSupported = muxers[1];

            if (whipSupported || rtmpSupported) {
                String supported = "";
                if (rtmpSupported) supported += "RTMP ";
                if (whipSupported) supported += "WHIP";
                log.info("FFmpeg 推流能力检测通过: path={}, 支持: {}", ffmpegPath, supported.trim());
                ffmpegStatus = whipSupported ? "OK" : "OK_RTMP_ONLY";
                ffmpegError = "";
            } else {
                log.warn("[S-4] FFmpeg 不支持 WHIP/RTMP，降级为协议模拟: path={}", ffmpegPath);
                ffmpegStatus = "WHIP_NOT_SUPPORTED";
                ffmpegError = "FFmpeg 可执行但不支持 WHIP/RTMP 推流。需安装普通版 ffmpeg（支持 RTMP）或特殊编译版（支持 WHIP）";
                diagnosticRecorder.record(DiagnosticCode.SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED, "-",
                        "FFmpeg 不支持 WHIP/RTMP，降级为协议模拟。path=" + ffmpegPath);
            }
        } catch (java.io.IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Cannot run program")) {
                log.warn("[S-4] FFmpeg 路径无效: {} - {}", ffmpegPath, msg);
                ffmpegStatus = "PATH_NOT_FOUND";
                ffmpegError = "FFmpeg 路径无效，文件不存在或不可执行: " + ffmpegPath;
            } else if (msg != null && msg.contains("超时")) {
                log.warn("[S-4] FFmpeg 执行超时: {}", ffmpegPath);
                ffmpegStatus = "EXECUTE_TIMEOUT";
                ffmpegError = "FFmpeg -muxers 执行超时（10s），可能进程卡死";
            } else {
                log.warn("[S-4] FFmpeg 执行失败: {} - {}", ffmpegPath, msg);
                ffmpegStatus = "PATH_NOT_FOUND";
                ffmpegError = "FFmpeg 执行失败: " + msg;
            }
            diagnosticRecorder.record(DiagnosticCode.SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED, "-",
                    "FFmpeg 能力检测失败: " + msg);
        } catch (Exception e) {
            log.warn("[S-4] FFmpeg 能力检测异常: {} - {}", ffmpegPath, e.getMessage());
            ffmpegStatus = "PATH_NOT_FOUND";
            ffmpegError = "FFmpeg 检测异常: " + e.getMessage();
            diagnosticRecorder.record(DiagnosticCode.SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED, "-",
                    "FFmpeg 能力检测失败: " + e.getMessage());
        }
    }

    /**
     * 执行 `ffmpeg -muxers` 同时检测 WHIP 和 RTMP(flv) muxer。
     * @return [0]=whipSupported, [1]=rtmpSupported(flv muxer)
     */
    private boolean[] checkMuxers(String ffmpegPath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-muxers");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 必须在 waitFor 之前用独立线程消费输出，否则 OS 管道缓冲区（Windows 约 4KB）
        // 被 ffmpeg -muxers 的大量输出填满后，进程阻塞等待消费，waitFor 永远不会返回
        StringBuilder output = new StringBuilder();
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                // 进程被 destroyForcibly 时可能抛出，忽略
            }
        }, "ffmpeg-muxers-reader");
        outputThread.setDaemon(true);
        outputThread.start();

        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            outputThread.interrupt();
            throw new IOException("ffmpeg -muxers 执行超时（10s）");
        }
        outputThread.join(2000);

        return parseMuxers(output.toString());
    }

    /**
     * 解析 {@code ffmpeg -muxers} 输出，检测 whip 与 flv muxer 是否存在。
     * <p>行格式随 ffmpeg 版本不同（"E" 后空格数不同，按空白分词解析以兼容两代格式）：
     * <ul>
     *   <li>ffmpeg ≤7.x：{@code "  E flv             FLV (Flash Video)"}</li>
     *   <li>ffmpeg 8.x：{@code "  E  flv             FLV (Flash Video)"}</li>
     * </ul>
     * tokens[0] 为 flags（含 "E" 表示 Muxing supported），tokens[1] 为 muxer 名。
     * banner/表头/分隔行不满足该结构，不产生误判。
     * @return [0]=whipSupported, [1]=rtmpSupported(flv muxer)
     */
    static boolean[] parseMuxers(String muxersOutput) {
        boolean whip = false;
        boolean flv = false;
        for (String line : muxersOutput.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length < 2 || !tokens[0].contains("E")) {
                continue;
            }
            if ("flv".equals(tokens[1])) {
                flv = true;
            } else if ("whip".equals(tokens[1])) {
                whip = true;
            }
        }
        return new boolean[]{whip, flv};
    }

    /**
     * 是否可真实推流（real-push-enabled=true 且 ffmpeg 支持至少一种协议）。
     */
    public boolean isRealPushAvailable() {
        return runtimeConfig.isLiveRealPushEnabled() && (whipSupported || rtmpSupported);
    }

    /**
     * 是否启用了真实推流配置（无论 ffmpeg 是否支持）。
     */
    public boolean isRealPushEnabled() {
        return runtimeConfig.isLiveRealPushEnabled();
    }

    /**
     * 指定 url_type 是否可真实推流。
     * @param urlType 0=Agora, 1=RTMP, 3=GB28181, 4=WebRTC(WHIP)
     */
    public boolean isPushAvailable(int urlType) {
        if (!runtimeConfig.isLiveRealPushEnabled()) return false;
        if (urlType == URL_TYPE_RTMP) return rtmpSupported;
        if (urlType == URL_TYPE_WEBRTC) return whipSupported;
        return false;
    }

    /**
     * 启动 ffmpeg 推流（根据 urlType 选择 RTMP/WHIP）。
     * @param videoId   推流唯一标识（用于后续 stopPush）
     * @param url       推流目标 URL（RTMP: rtmp://... / WHIP: http://...）
     * @param videoFile 视频文件绝对路径
     * @param urlType   1=RTMP, 4=WebRTC(WHIP)
     * @return true=启动成功；false=启动失败或不可用
     */
    public boolean startPush(String videoId, String url, String videoFile, int urlType) {
        if (!isPushAvailable(urlType)) {
            return false;
        }
        if (videoId == null || videoId.isBlank() || url == null || url.isBlank() || videoFile == null) {
            log.warn("启动推流参数无效: videoId={}, url={}, videoFile={}", videoId, url, videoFile);
            return false;
        }
        if (!Files.exists(Path.of(videoFile))) {
            log.warn("[S-4] 视频文件不存在: {}，降级为协议模拟", videoFile);
            diagnosticRecorder.record(DiagnosticCode.SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED, ServiceMethod.LIVE_START_PUSH.methodName(),
                    "视频文件不存在: " + videoFile);
            return false;
        }

        // 检查视频编码与目标协议的兼容性（-c copy 模式不转码，编码必须兼容）
        if (!checkVideoCodecCompatibility(runtimeConfig.getLiveFfmpegPath(), videoFile, urlType)) {
            log.warn("[S-4] 视频编码与目标协议不兼容，降级为协议模拟: file={}, urlType={}", videoFile, urlType);
            diagnosticRecorder.record(DiagnosticCode.SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED, ServiceMethod.LIVE_START_PUSH.methodName(),
                    "视频编码与目标协议不兼容: file=" + videoFile + ", urlType=" + urlType
                    + "。建议使用 H.264 + AAC 编码的 MP4 文件（兼容所有协议）。");
            return false;
        }

        // 若该 videoId 已有进程，先停止
        stopPush(videoId);

        List<String> cmd;
        String proto;
        if (urlType == URL_TYPE_RTMP) {
            cmd = buildRtmpCommand(runtimeConfig.getLiveFfmpegPath(), videoFile, url);
            proto = "RTMP";
        } else {
            cmd = buildWhipCommand(runtimeConfig.getLiveFfmpegPath(), videoFile, url);
            proto = "WHIP";
        }
        log.info("启动 {} 推流: videoId={}, url={}, videoFile={}", proto, videoId, url, videoFile);
        log.debug("ffmpeg 命令: {}", String.join(" ", cmd));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            activeProcesses.put(videoId, process);

            // 单独线程消费 ffmpeg 输出，避免缓冲区满阻塞，并记录到日志
            Thread outputThread = new Thread(() -> consumeProcessOutput(process, videoId),
                    "ffmpeg-" + proto.toLowerCase() + "-" + videoId);
            outputThread.setDaemon(true);
            outputThread.start();

            log.info("{} 推流进程已启动: videoId={}, pid={}", proto, videoId, process.pid());
            return true;
        } catch (IOException e) {
            log.error("[S-4] 启动 ffmpeg 推流失败: videoId={}, error={}", videoId, e.getMessage(), e);
            diagnosticRecorder.record(DiagnosticCode.SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED, ServiceMethod.LIVE_START_PUSH.methodName(),
                    "启动 ffmpeg 推流失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 构建 ffmpeg 推 RTMP 流的命令。
     * <p>RTMP 推流使用 flv 封装，普通预编译 ffmpeg 即支持。
     * <p>-stream_loop -1 循环推流；-re 按原始帧率推送；-c copy 直接拷贝（无需转码）。
     */
    private List<String> buildRtmpCommand(String ffmpegPath, String videoFile, String url) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-re");
        cmd.add("-stream_loop");
        cmd.add("-1");
        cmd.add("-i");
        cmd.add(videoFile);
        // RTMP 推流：直接拷贝编码（避免转码开销），flv 封装
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-f");
        cmd.add("flv");
        cmd.add(url);
        return cmd;
    }

    /**
     * 检查视频文件的编码与目标推流协议是否兼容（-c copy 模式不转码）。
     * <p>RTMP (FLV): 视频 H.264，音频 AAC/MP3</p>
     * <p>WebRTC (WHIP): 视频 H.264/VP8，音频 Opus（但 buildWhipCommand 使用转码，无需检查）</p>
     * @return true=兼容或无法检测（放行）；false=明确不兼容
     */
    private boolean checkVideoCodecCompatibility(String ffmpegPath, String videoFile, int urlType) {
        // WHIP 使用 buildWhipCommand 转码（libx264 + libopus），不需要检查源文件编码
        if (urlType == URL_TYPE_WEBRTC) {
            return true;
        }

        String videoCodec = getVideoCodec(ffmpegPath, videoFile);
        if (videoCodec == null) {
            // ffprobe 失败时不阻止推流（避免因 ffprobe 问题导致完全无法推流）
            log.warn("无法检测视频编码，跳过兼容性检查: file={}", videoFile);
            return true;
        }

        log.info("视频编码检测: file={}, codec={}", videoFile, videoCodec);

        if (urlType == URL_TYPE_RTMP) {
            // RTMP (FLV) 仅支持 H.264 视频
            if (!"h264".equals(videoCodec)) {
                log.warn("RTMP 推流要求 H.264 编码，当前视频编码为 {}，不兼容", videoCodec);
                return false;
            }
        }
        return true;
    }

    /**
     * 使用 ffprobe 检测视频文件的视频编码。
     * @return 编码名称（如 "h264"、"hevc"），检测失败返回 null
     */
    private String getVideoCodec(String ffmpegPath, String videoFile) {
        // ffprobe 通常与 ffmpeg 在同一目录
        Path ffprobePath = Path.of(ffmpegPath).resolveSibling("ffprobe");
        String ffprobe = ffprobePath.toAbsolutePath().toString();
        if (!Files.exists(ffprobePath)) {
            // 尝试直接使用 ffprobe（依赖 PATH）
            ffprobe = "ffprobe";
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(ffprobe,
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=codec_name",
                    "-of", "csv=p=0",
                    videoFile);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("ffprobe 执行超时: file={}", videoFile);
                return null;
            }
            return output.isEmpty() ? null : output.toLowerCase();
        } catch (Exception e) {
            log.warn("ffprobe 检测视频编码失败: file={}, error={}", videoFile, e.getMessage());
            return null;
        }
    }

    /**
     * 构建 ffmpeg 推 WHIP 流的命令。
     * <p>命令格式参考 ossrs/ffmpeg-webrtc，H264 baseline + Opus。
     * <p>-stream_loop -1 实现循环推流；-re 按原始帧率推送。
     */
    private List<String> buildWhipCommand(String ffmpegPath, String videoFile, String url) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-re");
        cmd.add("-stream_loop");
        cmd.add("-1");
        cmd.add("-i");
        cmd.add(videoFile);
        // 视频编码：H264 baseline profile（WHIP 兼容性要求）
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-profile:v");
        cmd.add("baseline");
        cmd.add("-preset");
        cmd.add("ultrafast");
        cmd.add("-tune");
        cmd.add("zerolatency");
        cmd.add("-bf");
        cmd.add("0");
        // 音频编码：Opus（WebRTC 要求）
        cmd.add("-c:a");
        cmd.add("libopus");
        cmd.add("-ar");
        cmd.add("48000");
        cmd.add("-ac");
        cmd.add("2");
        cmd.add("-ab");
        cmd.add("32k");
        // WHIP 输出格式
        cmd.add("-f");
        cmd.add("whip");
        cmd.add(url);
        return cmd;
    }

    /**
     * 消费 ffmpeg 进程输出，记录到日志（DEBUG 级别，避免日志爆炸）。
     */
    private void consumeProcessOutput(Process process, String videoId) {
        try (InputStream is = process.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[ffmpeg-whip:{}] {}", videoId, line);
            }
        } catch (IOException e) {
            log.debug("[ffmpeg-whip:{}] 输出流读取结束: {}", videoId, e.getMessage());
        }
    }

    /**
     * 停止指定 videoId 的推流进程。
     */
    public void stopPush(String videoId) {
        if (videoId == null) {
            return;
        }
        Process process = activeProcesses.remove(videoId);
        if (process != null && process.isAlive()) {
            process.destroy();
            log.info("WHIP 推流进程已停止: videoId={}", videoId);
        }
    }

    /**
     * 停止所有活跃推流进程（应用关闭 @PreDestroy 与设备下线共用，TC-LIVE-021），防止泄漏。
     */
    @PreDestroy
    public void stopAll() {
        if (activeProcesses.isEmpty()) {
            return;
        }
        log.info("停止 {} 个活跃推流进程（应用关闭或设备下线）", activeProcesses.size());
        for (Map.Entry<String, Process> entry : activeProcesses.entrySet()) {
            Process process = entry.getValue();
            if (process.isAlive()) {
                process.destroy();
                log.info("已停止推流进程: videoId={}", entry.getKey());
            }
        }
        activeProcesses.clear();
    }

    /**
     * 获取当前活跃推流的 videoId 列表（供状态查询）。
     */
    public List<String> getActiveVideoIds() {
        return new ArrayList<>(activeProcesses.keySet());
    }

    /**
     * 重新检测 ffmpeg WHIP 能力（用户完成安装/配置后手动触发）。
     * <p>重置检测标志后重新执行检测逻辑。</p>
     */
    public void refresh() {
        detected = false;
        whipSupported = false;
        rtmpSupported = false;
        ffmpegStatus = "NOT_CHECKED";
        ffmpegError = "";
        detectCapabilities();
    }

    /**
     * 获取直播推流能力状态和限制清单（供前端展示引导用户）。
     * <p>返回结构：
     * <pre>{@code
     * {
     *   "realPushEnabled": true/false,     // 是否启用真实推流配置
     *   "whipSupported": true/false,       // ffmpeg 是否支持 WHIP
     *   "realPushAvailable": true/false,   // 是否可真实推流（enabled && supported）
     *   "ffmpegPath": "ffmpeg",            // 配置的 ffmpeg 路径
     *   "videoDir": "D:/videos",          // 配置的视频目录
     *   "videosFound": ["165-0-7-normal.mp4", "default.mp4"],  // 目录中已存在的视频文件
     *   "activePushCount": 2,             // 当前活跃推流进程数
     *   "limitations": [                  // 限制清单（空数组=无限制，功能完整）
     *     {
     *       "code": "S-4",
     *       "title": "无法验证平台 WebRTC 接收链路",
     *       "reason": "本机 ffmpeg 不支持 WHIP 推流",
     *       "action": "安装 ffmpeg ≥8.0 且编译时启用 --enable-muxer=whip...",
     *       "afterFix": "模拟器可真实推送 WebRTC 视频流，验证平台接收/解码/分发链路"
     *     }
     *   ]
     * }
     * }</pre>
     */
    public Map<String, Object> getCapability() {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("realPushEnabled", runtimeConfig.isLiveRealPushEnabled());
        cap.put("rtmpSupported", rtmpSupported);
        cap.put("whipSupported", whipSupported);
        cap.put("realPushAvailable", isRealPushAvailable());
        cap.put("ffmpegPath", runtimeConfig.getLiveFfmpegPath());
        cap.put("ffmpegStatus", ffmpegStatus);
        cap.put("ffmpegError", ffmpegError);
        cap.put("videoDir", runtimeConfig.getLiveVideoDir());
        cap.put("videosFound", listVideoFiles());
        cap.put("activePushCount", activeProcesses.size());

        List<Map<String, String>> limitations = buildLimitations();
        cap.put("limitations", limitations);
        return cap;
    }

    /**
     * 扫描 video-dir 下已存在的视频文件列表（供前端展示已有资源）。
     */
    private List<String> listVideoFiles() {
        List<String> files = new ArrayList<>();
        String videoDir = runtimeConfig.getLiveVideoDir();
        if (videoDir == null || videoDir.isBlank()) {
            return files;
        }
        Path dir = Path.of(videoDir);
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String name = p.getFileName().toString().toLowerCase();
                      return name.endsWith(".mp4") || name.endsWith(".flv") || name.endsWith(".mkv");
                  })
                  .forEach(p -> files.add(p.getFileName().toString()));
        } catch (IOException e) {
            log.warn("扫描视频目录失败: {} - {}", videoDir, e.getMessage());
        }
        return files;
    }

    /**
     * 根据当前能力状态动态构建限制清单。
     * <p>限制按优先级排序：未启用 → ffmpeg 不支持 → 无视频目录 → 无视频文件。</p>
     */
    private List<Map<String, String>> buildLimitations() {
        List<Map<String, String>> limitations = new ArrayList<>();

        // 限制1：未启用真实推流
        if (!runtimeConfig.isLiveRealPushEnabled()) {
            Map<String, String> lim = new LinkedHashMap<>();
            lim.put("code", "CONFIG");
            lim.put("title", "未启用真实推流，仅协议模拟");
            lim.put("reason", "real-push-enabled=false（默认）");
            lim.put("action", "在下方配置中开启真实推流，并设置 ffmpeg-path 和 video-dir");
            lim.put("afterFix", "模拟器将对 url_type=4 (WebRTC) 的 live_start_push 启动真实 ffmpeg 推流进程");
            limitations.add(lim);
            return limitations; // 未启用时其他限制无意义
        }

        // 限制2：ffmpeg 检测失败（根据细分状态给出精确提示）
        if (!whipSupported && !rtmpSupported) {
            // RTMP 和 WHIP 都不支持
            Map<String, String> lim = new LinkedHashMap<>();
            lim.put("code", "S-4");
            switch (ffmpegStatus) {
                case "PATH_EMPTY" -> {
                    lim.put("title", "FFmpeg 路径未配置");
                    lim.put("reason", ffmpegError);
                    lim.put("action", "在下方配置中填写 FFmpeg 可执行文件路径。安装普通版 ffmpeg 即可支持 RTMP 推流");
                }
                case "PATH_NOT_FOUND" -> {
                    lim.put("title", "FFmpeg 路径无效");
                    lim.put("reason", ffmpegError);
                    lim.put("action", "检查路径是否正确，确保文件存在且可执行。Windows 下需填完整路径如 C:\\ffmpeg\\bin\\ffmpeg.exe，或将 ffmpeg 加入 PATH 后填 ffmpeg");
                }
                case "EXECUTE_TIMEOUT" -> {
                    lim.put("title", "FFmpeg 执行超时");
                    lim.put("reason", ffmpegError);
                    lim.put("action", "ffmpeg -muxers 执行超过 10 秒未完成，可能进程卡死。尝试重启系统或更换 ffmpeg 版本");
                }
                case "WHIP_NOT_SUPPORTED" -> {
                    lim.put("title", "FFmpeg 不支持 RTMP/WHIP 推流");
                    lim.put("reason", ffmpegError);
                    lim.put("action", FfmpegInstaller.isWindowsOs()
                            ? "安装普通版 ffmpeg（winget install ffmpeg 或从 ffmpeg.org 下载），即可支持 RTMP 推流。如需 WebRTC 直推需特殊编译版（--enable-muxer=whip）"
                            : "安装普通版 ffmpeg 即可支持 RTMP 推流（Linux: apt-get install ffmpeg；Docker 镜像已内置，FFmpeg 路径填 ffmpeg 即可）。如需 WebRTC 直推需特殊编译版（--enable-muxer=whip）");
                }
                default -> {
                    lim.put("title", "FFmpeg 检测未通过");
                    lim.put("reason", ffmpegError.isEmpty() ? "未知原因" : ffmpegError);
                    lim.put("action", "请检查 FFmpeg 路径和版本");
                }
            }
            lim.put("afterFix", "模拟器可真实推送 RTMP 视频流（平台转 WebRTC 后可验证完整链路）");
            limitations.add(lim);
        } else if (!whipSupported && rtmpSupported) {
            // RTMP 支持但 WHIP 不支持（信息提示，非阻断性限制）
            Map<String, String> lim = new LinkedHashMap<>();
            lim.put("code", "S-4");
            lim.put("title", "WebRTC (WHIP) 直推不可用");
            lim.put("reason", "当前 ffmpeg 支持 RTMP 但不支持 WHIP muxer（需 ffmpeg ≥8.0 且 --enable-muxer=whip 编译）");
            lim.put("action", "如需 WebRTC 直推，需安装特殊编译版 ffmpeg。若第三方平台支持 RTMP→WebRTC 转换，当前 RTMP 推流已足够");
            lim.put("afterFix", "模拟器可直推 WebRTC 视频流（无需平台转换）");
            limitations.add(lim);
        }

        String videoDir = runtimeConfig.getLiveVideoDir();

        // 限制3：video-dir 未配置
        if (videoDir == null || videoDir.isBlank()) {
            Map<String, String> lim = new LinkedHashMap<>();
            lim.put("code", "CONFIG");
            lim.put("title", "无视频源目录");
            lim.put("reason", "video-dir 未配置");
            lim.put("action", "在下方配置中设置 video-dir 指向视频文件目录");
            lim.put("afterFix", "模拟器将从该目录按 {camera_index}-{video_type}.mp4 规则查找视频文件");
            limitations.add(lim);
            return limitations;
        }

        // 限制4：video-dir 目录不存在
        if (!Files.isDirectory(Path.of(videoDir))) {
            Map<String, String> lim = new LinkedHashMap<>();
            lim.put("code", "CONFIG");
            lim.put("title", "视频源目录不存在");
            lim.put("reason", "配置的 video-dir 不是有效目录: " + videoDir);
            lim.put("action", "创建该目录并放入视频文件，或修改 video-dir 指向已存在的目录");
            lim.put("afterFix", "模拟器可从该目录加载视频文件推送");
            limitations.add(lim);
            return limitations;
        }

        // 限制5：目录中无视频文件
        List<String> videos = listVideoFiles();
        if (videos.isEmpty()) {
            Map<String, String> lim = new LinkedHashMap<>();
            lim.put("code", "CONFIG");
            lim.put("title", "视频目录中无视频文件");
            lim.put("reason", "video-dir (" + videoDir + ") 中无 .mp4/.flv/.mkv 文件");
            lim.put("action", "在目录中放置视频文件，命名规则: {camera_index}-{video_type}.mp4（如 165-0-7-normal.mp4），或放置 default.mp4 作为通用回退");
            lim.put("afterFix", "模拟器推流时按 video_id 解析相机类型，匹配对应视频文件推送");
            limitations.add(lim);
        }

        return limitations;
    }
}
