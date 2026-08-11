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
import jakarta.annotation.PostConstruct;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 直播流模拟器。
 * <p>处理 live_start_push / live_stop_push / live_set_quality / live_camera_change / live_lens_change 命令，
 * 维护推流状态。当 {@code simulator.live.real-push-enabled=true} 且本机 ffmpeg 支持 WHIP 时，
 * 对 url_type=4 (WebRTC) 的推流启动真实 ffmpeg 进程推送视频文件。</p>
 * <p>全部为同步 Service（仅 services_reply，无 Events 进度事件）。</p>
 * <p>三 Dock 差异：
 * <ul>
 *   <li>Dock1 不支持 live_camera_change（占位 result=0，不更新状态）</li>
 *   <li>Dock2/Dock3 支持 live_camera_change（解析 video_id + camera_position 更新推流记录）</li>
 *   <li>三 Dock 均支持 live_lens_change（解析 video_type 全局更新）</li>
 * </ul>
 * <p>WHIP 真实推流逻辑委托 {@link FfmpegWhipPusher}，本类只负责协议层状态维护和 video_id→视频文件映射。</p>
 * <p>详见 DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html">直播（Dock3）</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/live.html">Dock2</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/live.html">Dock1</a>。
 */
@Component
public class LiveStreamSimulator {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamSimulator.class);

    /** camera_position 默认值：0=舱内（DJI 枚举 0=舱内,1=舱外） */
    private static final int DEFAULT_CAMERA_POSITION = 0;
    /** video_type 默认值：normal（DJI 枚举 normal/zoom/wide/ir） */
    private static final String DEFAULT_VIDEO_TYPE = "normal";

    private final ServiceCommandHandler commandHandler;
    private final RuntimeConfig runtimeConfig;
    private final FfmpegWhipPusher ffmpegPusher;
    private final DiagnosticLogRecorder diagnosticRecorder;

    /** 当前活跃的推流列表（支持多路同时推流） */
    private final List<LiveStream> activeStreams = new CopyOnWriteArrayList<>();
    /** 全局镜头类型（live_lens_change 设置，无 video_id，对所有推流生效） */
    private volatile String videoType = DEFAULT_VIDEO_TYPE;

    public LiveStreamSimulator(ServiceCommandHandler commandHandler,
                                RuntimeConfig runtimeConfig, FfmpegWhipPusher ffmpegPusher,
                                DiagnosticLogRecorder diagnosticRecorder) {
        this.commandHandler = commandHandler;
        this.runtimeConfig = runtimeConfig;
        this.ffmpegPusher = ffmpegPusher;
        this.diagnosticRecorder = diagnosticRecorder;
    }

    @PostConstruct
    public void init() {
        commandHandler.setLiveHandler(this::handle);
        log.info("LiveStreamSimulator 已注册直播命令处理器，真实推流可用: {}", ffmpegPusher.isRealPushAvailable());
    }

    /**
     * 统一路由直播指令（由 ServiceCommandHandler 调用）。
     * @param method 指令方法名
     * @param data   指令 data
     * @return services_reply 的 output（含 result 字段）
     */
    public Map<String, Object> handle(String method, JsonNode data) {
        log.info("处理直播命令: method={}", method);

        return switch (method) {
            case "live_start_push" -> handleStartPush(data);
            case "live_stop_push" -> handleStopPush(data);
            case "live_set_quality" -> handleSetQuality(data);
            case "live_camera_change" -> handleCameraChange(data);
            case "live_lens_change" -> handleLensChange(data);
            default -> Map.of("result", 0);
        };
    }

    /**
     * 开始推流：记录推流信息，回 result=0。
     * <p>video_id 为推流唯一标识，重复 start_push 同一 video_id 时幂等更新，避免产生重复记录。</p>
     * <p>若 url_type=0 (RTMP) 或 url_type=4 (WebRTC) 且 ffmpeg 支持对应协议，启动真实推流进程；否则仅协议模拟。</p>
     */
    private Map<String, Object> handleStartPush(JsonNode data) {
        if (data == null) {
            return Map.of("result", 1);
        }
        String videoId = data.path("video_id").asText();
        String url = data.path("url").asText();
        int urlType = data.path("url_type").asInt();
        int quality = data.path("video_quality").asInt();

        // 幂等：先移除已存在的同 videoId 记录，再添加，保证 video_id 唯一
        // 新推流默认 camera_position=0（舱内）
        activeStreams.removeIf(s -> s.videoId().equals(videoId));
        LiveStream stream = new LiveStream(videoId, url, urlType, quality, DEFAULT_CAMERA_POSITION);
        activeStreams.add(stream);
        log.info("直播推流已启动: videoId={}, urlType={}, quality={}", videoId, urlType, quality);

        // 尝试真实推流（RTMP 或 WebRTC）
        tryStartPush(videoId, url, urlType);
        return Map.of("result", 0);
    }

    /**
     * 尝试启动真实推流。
     * <p>url_type=0 (RTMP) 或 url_type=4 (WebRTC) 且 ffmpeg 支持对应协议时启动；
     * 否则降级为协议模拟（记诊断日志）。</p>
     */
    private void tryStartPush(String videoId, String url, int urlType) {
        if (urlType != FfmpegWhipPusher.URL_TYPE_RTMP && urlType != FfmpegWhipPusher.URL_TYPE_WEBRTC) {
            // RTSP/GB28181 不真实推流，仅协议模拟
            if (ffmpegPusher.isRealPushEnabled()) {
                log.info("url_type={} 非 RTMP/WebRTC，仅协议模拟（不支持真实推流）", urlType);
            }
            return;
        }
        if (!ffmpegPusher.isPushAvailable(urlType)) {
            // ffmpeg 不支持该协议，降级为协议模拟
            String proto = (urlType == FfmpegWhipPusher.URL_TYPE_RTMP) ? "RTMP" : "WHIP";
            log.debug("ffmpeg 不支持 {}，videoId={} 仅协议模拟", proto, videoId);
            return;
        }
        String videoFile = resolveVideoFile(videoId);
        boolean started = ffmpegPusher.startPush(videoId, url, videoFile, urlType);
        if (started) {
            String proto = (urlType == FfmpegWhipPusher.URL_TYPE_RTMP) ? "RTMP" : "WHIP";
            log.info("{} 真实推流已启动: videoId={}", proto, videoId);
        } else {
            String proto = (urlType == FfmpegWhipPusher.URL_TYPE_RTMP) ? "RTMP" : "WHIP";
            log.warn("{} 真实推流启动失败，降级为协议模拟: videoId={}", proto, videoId);
        }
    }

    /**
     * 根据 video_id 解析对应的视频文件路径。
     * <p>video_id 格式：{sn}/{camera_index}/{video_type}-{index}，如 "SN/165-0-7/normal-0"。</p>
     * <p>映射规则：video-dir 下查找 {camera_index}-{video_type}.mp4（如 165-0-7-normal.mp4）；
     * 找不到时回退到 default.mp4；仍找不到返回空字符串（由调用方降级处理）。</p>
     * <p>配置来源：{@link RuntimeConfig}（yml 提供默认值，前端 REST API 可运行时覆盖）。</p>
     */
    private String resolveVideoFile(String videoId) {
        String dir = runtimeConfig.getLiveVideoDir();
        if (dir == null || dir.isBlank()) {
            return "";
        }
        // 解析 video_id: {sn}/{camera_index}/{video_type}-{index}
        String[] parts = videoId.split("/");
        if (parts.length < 3) {
            return "";
        }
        String cameraIndex = parts[1];          // 如 165-0-7
        String videoIndex = parts[2];           // 如 normal-0
        String videoTypeName = videoIndex.split("-")[0];  // 如 normal

        // 优先查找 {camera_index}-{video_type}.mp4
        String primaryFile = Path.of(dir, cameraIndex + "-" + videoTypeName + ".mp4").toString();
        if (java.nio.file.Files.exists(java.nio.file.Path.of(primaryFile))) {
            return primaryFile;
        }
        // 回退到 default.mp4
        String defaultFile = Path.of(dir, "default.mp4").toString();
        if (java.nio.file.Files.exists(java.nio.file.Path.of(defaultFile))) {
            log.info("未找到 {}，回退到 default.mp4", primaryFile);
            return defaultFile;
        }
        log.warn("视频文件不存在: {} 和 {}，将降级为协议模拟", primaryFile, defaultFile);
        diagnosticRecorder.record(DiagnosticCode.SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED, "live_start_push",
                "视频文件不存在: " + primaryFile + " 和 default.mp4");
        return "";
    }

    /**
     * 停止推流：移除推流记录并停止 ffmpeg 进程，回 result=0。
     */
    private Map<String, Object> handleStopPush(JsonNode data) {
        if (data != null) {
            String videoId = data.path("video_id").asText();
            activeStreams.removeIf(s -> s.videoId().equals(videoId));
            ffmpegPusher.stopPush(videoId);
            log.info("直播推流已停止: videoId={}", videoId);
        }
        return Map.of("result", 0);
    }

    /**
     * 设置清晰度：更新推流记录的 quality，回 result=0。
     * <p>注：ffmpeg 推流进程不动态调整码率（标记 TODO，需重启进程实现）。</p>
     */
    private Map<String, Object> handleSetQuality(JsonNode data) {
        if (data != null) {
            String videoId = data.path("video_id").asText();
            int quality = data.path("video_quality").asInt();
            // 使用索引遍历，避免 CopyOnWriteArrayList 在并发修改时 indexOf 返回 -1 导致 IOOBE
            for (int i = 0; i < activeStreams.size(); i++) {
                LiveStream stream = activeStreams.get(i);
                if (stream.videoId().equals(videoId)) {
                    activeStreams.set(i, new LiveStream(
                            videoId, stream.url(), stream.urlType(), quality, stream.cameraPosition()));
                    break;
                }
            }
            log.info("直播清晰度已设置: videoId={}, quality={}", videoId, quality);
            // TODO: 若需真实推流反映清晰度变化，需重启 ffmpeg 进程调整码率
        }
        return Map.of("result", 0);
    }

    /**
     * 切换直播相机：Dock2/Dock3 解析 video_id + camera_position 更新推流记录；Dock1 占位应答。
     * <p>DJI 枚举：camera_position 0=舱内,1=舱外。</p>
     * <p>核实依据：[Dock1 live.html] 无 live_camera_change 指令；[Dock2/Dock3 live.html] 均有该指令。</p>
     */
    private Map<String, Object> handleCameraChange(JsonNode data) {
        DeviceType dockType = runtimeConfig.getDockType();
        if (dockType == DeviceType.DOCK1) {
            log.warn("Dock1 不支持 live_camera_change 指令，返回占位 result=0（不更新状态）");
            return Map.of("result", 0);
        }
        if (data == null) {
            return Map.of("result", 0);
        }
        String videoId = data.path("video_id").asText();
        int cameraPosition = data.path("camera_position").asInt();
        for (int i = 0; i < activeStreams.size(); i++) {
            LiveStream stream = activeStreams.get(i);
            if (stream.videoId().equals(videoId)) {
                activeStreams.set(i, new LiveStream(
                        videoId, stream.url(), stream.urlType(), stream.quality(), cameraPosition));
                log.info("直播相机已切换: videoId={}, cameraPosition={}", videoId, cameraPosition);
                break;
            }
        }
        return Map.of("result", 0);
    }

    /**
     * 切换直播镜头：解析 video_type 全局更新，回 result=0。
     * <p>DJI 枚举：video_type ∈ normal/zoom/wide/ir。无 video_id，对所有推流生效。</p>
     * <p>核实依据：三 Dock live.html 均有 live_lens_change 指令，Data 仅含 video_type。</p>
     */
    private Map<String, Object> handleLensChange(JsonNode data) {
        if (data != null) {
            String newVideoType = data.path("video_type").asText();
            if (!newVideoType.isEmpty()) {
                this.videoType = newVideoType;
                log.info("直播镜头已切换: videoType={}", newVideoType);
                // TODO: 若需真实推流反映镜头切换，需重启 ffmpeg 进程使用新视频文件
            }
        }
        return Map.of("result", 0);
    }

    /**
     * 获取当前活跃推流列表（供 Web 控制台使用）。
     * <p>每条记录包含 video_id/url/url_type/quality/camera_position；全局 video_type 单独通过 {@link #getVideoType()} 获取。</p>
     */
    public List<Map<String, Object>> getActiveStreams() {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (LiveStream s : activeStreams) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("video_id", s.videoId());
            m.put("url", s.url());
            m.put("url_type", s.urlType());
            m.put("quality", s.quality());
            m.put("camera_position", s.cameraPosition());
            result.add(m);
        }
        return result;
    }

    /** 获取全局镜头类型（live_lens_change 设置）。 */
    public String getVideoType() {
        return videoType;
    }

    /** 直播流记录 */
    private record LiveStream(String videoId, String url, int urlType, int quality, int cameraPosition) {}
}
