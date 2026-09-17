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
import ltd.cdmi.dji.cloudapi.sdk.codec.MessageCodec;
import ltd.cdmi.dji.cloudapi.sdk.command.service.live.LiveCameraChangeRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.live.LiveLensChangeRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.live.LiveSetQualityRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.live.LiveStartPushRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.live.LiveStopPushRequest;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.DrcMethod;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.ServiceMethod;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.media.FfmpegWhipPusher;
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
 * <p>处理直播相关命令，维护推流状态。当 {@code simulator.live.real-push-enabled=true} 且本机 ffmpeg 支持 WHIP 时，
 * 对 url_type=4 (WebRTC) 的推流启动真实 ffmpeg 进程推送视频文件。</p>
 * <p>Pilot 与 Dock 直播差异：
 * <ul>
 *   <li><b>通用 Service 指令</b>（Pilot + Dock）：live_start_push / live_stop_push / live_set_quality，
 *       通过 Service Topic 接收，services_reply 回复，协议一致</li>
 *   <li><b>镜头切换差异</b>：Dock 通过 Service Topic 收 live_lens_change（video_type=normal/zoom/wide/ir，全局更新），
 *       其他 Pilot 机型（RC Plus/RC Pro）通过 Service Topic 收 live_lens_change（video_id + video_type=ir/normal/wide/zoom，按 video_id 精准切换），
 *       RC Plus 2 通过 DRC Topic 收 drc_live_lens_change（payload_index + video_type=thermal/wide/zoom，无 normal）</li>
 *   <li><b>相机切换</b>：仅 Dock2/Dock3 通过 Service Topic 收 live_camera_change，Pilot 无此指令</li>
 * </ul>
 * <p>三 Dock 差异：
 * <ul>
 *   <li>Dock1 不支持 live_camera_change（占位 result=0，不更新状态）</li>
 *   <li>Dock2/Dock3 支持 live_camera_change（解析 video_id + camera_position 更新推流记录）</li>
 *   <li>三 Dock 均支持 live_lens_change（解析 video_type 全局更新）</li>
 * </ul>
 * <p>WHIP 真实推流逻辑委托 {@link FfmpegWhipPusher}，本类只负责协议层状态维护和 video_id→视频文件映射。</p>
 * <p>推流记录（TC-LIVE-023~025）：每次 live_start_push 生成一条记录（平台下发地址 + 实际推流地址 + 状态 + 失败原因），
 * 失败记录保留供排错（activeStreams 移除但 pushRecords 保留），live_stop_push/设备下线更新状态为 stopped，
 * 有界保留最近 {@value #MAX_PUSH_RECORDS} 条，REST /api/live/push-records 供前端直播推流面板展示。</p>
 * <p>详见 DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/dji-rc-plus-2/live.html">直播（Pilot）</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html">直播（Dock3）</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/live.html">Dock2</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/live.html">Dock1</a>。
 */
@Component
public class LiveStreamSimulator {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamSimulator.class);

    /** camera_position 默认值：0=舱内（DJI 枚举 0=舱内,1=舱外） */
    private static final int DEFAULT_CAMERA_POSITION = 0;
    /** video_type 默认值：normal（Dock/RC Plus 枚举 ir/normal/wide/zoom；RC Plus 2 枚举 thermal/wide/zoom，无 normal） */
    private static final String DEFAULT_VIDEO_TYPE = "normal";

    /** 推流记录状态：推流中（TC-LIVE-023） */
    private static final String STATUS_PUSHING = "pushing";
    /** 推流记录状态：已停止（live_stop_push 或设备下线，TC-LIVE-025） */
    private static final String STATUS_STOPPED = "stopped";
    /** 推流记录状态：失败（TC-LIVE-022 场景，记录保留供排错，TC-LIVE-023） */
    private static final String STATUS_FAILED = "failed";
    /** 推流记录上限（TC-LIVE-025，超出淘汰最旧记录） */
    private static final int MAX_PUSH_RECORDS = 20;

    private final ServiceCommandHandler commandHandler;
    private final DrcCommandHandler drcCommandHandler;
    private final RuntimeConfig runtimeConfig;
    private final FfmpegWhipPusher ffmpegPusher;
    private final DiagnosticLogRecorder diagnosticRecorder;

    /** 当前活跃的推流列表（支持多路同时推流） */
    private final List<LiveStream> activeStreams = new CopyOnWriteArrayList<>();
    /** 推流记录（含失败记录，最新在前，有界 {@link #MAX_PUSH_RECORDS} 条，TC-LIVE-023~025） */
    private final List<PushRecord> pushRecords = new CopyOnWriteArrayList<>();
    /** 全局镜头类型（lens_change 设置，无 video_id，对所有推流生效） */
    private volatile String videoType = DEFAULT_VIDEO_TYPE;

    public LiveStreamSimulator(ServiceCommandHandler commandHandler,
                                DrcCommandHandler drcCommandHandler,
                                RuntimeConfig runtimeConfig, FfmpegWhipPusher ffmpegPusher,
                                DiagnosticLogRecorder diagnosticRecorder) {
        this.commandHandler = commandHandler;
        this.drcCommandHandler = drcCommandHandler;
        this.runtimeConfig = runtimeConfig;
        this.ffmpegPusher = ffmpegPusher;
        this.diagnosticRecorder = diagnosticRecorder;
    }

    @PostConstruct
    public void init() {
        commandHandler.setLiveHandler(this::handle);
        // Pilot 模式：镜头切换走 DRC Topic（drc_live_lens_change），不通过 Service Topic
        if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
            drcCommandHandler.registerHandler(DrcMethod.DRC_LIVE_LENS_CHANGE.methodName(), this::handleDrcLensChange);
            log.info("Pilot 模式: 已注册 drc_live_lens_change 到 DRC 通道");
        }
        log.info("LiveStreamSimulator 已注册直播命令处理器，真实推流可用: {}", ffmpegPusher.isRealPushAvailable());
    }

    /**
     * 统一路由直播指令（由 ServiceCommandHandler 调用，Service Topic 下行）。
     * <p>Pilot 模式下，RC Plus 2 的镜头切换走 DRC Topic，其他 Pilot 机型走 Service Topic：
     * <ul>
     *   <li>live_lens_change → RC Plus 2 走 DRC Topic（drc_live_lens_change），Service Topic 收到时容错返回 result=0；其他 Pilot 机型走 Service Topic（含 video_id，按 video_id 精准切换）</li>
     *   <li>live_camera_change → Pilot 无此指令，Service Topic 收到时容错返回 result=0</li>
     * </ul>
     * @param method 指令方法名
     * @param data   指令 data
     * @return services_reply 的 output（含 result 字段）
     */
    public Map<String, Object> handle(String method, JsonNode data) {
        log.info("处理直播命令: method={}", method);

        // Pilot 模式下，RC Plus 2 的 live_lens_change 走 DRC Topic，其他 Pilot 机型走 Service Topic
        if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
            if (ServiceMethod.LIVE_LENS_CHANGE.methodName().equals(method) && runtimeConfig.getControllerType() == RcModel.RC_PLUS_2) {
                log.warn("RC Plus 2: live_lens_change 应走 DRC Topic（drc_live_lens_change），Service Topic 收到时容错返回 result=0");
                return Map.of("result", 0);
            }
            if (ServiceMethod.LIVE_CAMERA_CHANGE.methodName().equals(method)) {
                log.warn("Pilot 模式无 live_camera_change 指令，容错返回 result=0");
                return Map.of("result", 0);
            }
        }

        // switch case 标签要求编译时常量，ServiceMethod 枚举的 methodName() 为运行时调用，改用 if-else 链
        if (ServiceMethod.LIVE_START_PUSH.methodName().equals(method)) {
            return handleStartPush(data);
        }
        if (ServiceMethod.LIVE_STOP_PUSH.methodName().equals(method)) {
            return handleStopPush(data);
        }
        if (ServiceMethod.LIVE_SET_QUALITY.methodName().equals(method)) {
            return handleSetQuality(data);
        }
        if (ServiceMethod.LIVE_CAMERA_CHANGE.methodName().equals(method)) {
            return handleCameraChange(data);
        }
        if (ServiceMethod.LIVE_LENS_CHANGE.methodName().equals(method)) {
            return handleLensChange(data);
        }
        return Map.of("result", 0);
    }

    /**
     * 开始推流：记录推流信息，回 result=0。
     * <p>video_id 为推流唯一标识，重复 start_push 同一 video_id 时幂等更新，避免产生重复记录。</p>
     * <p>若 url_type=1 (RTMP) 或 url_type=4 (WebRTC) 且 ffmpeg 支持对应协议，启动真实推流进程；否则仅协议模拟。</p>
     * <p>推流记录（TC-LIVE-023）：成功记录 status=pushing，失败记录 status=failed + 失败原因并保留（activeStreams 移除但 pushRecords 不移除）。</p>
     */
    private Map<String, Object> handleStartPush(JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return Map.of("result", 1);
        }
        var req = MessageCodec.fromJson(data.toString(), LiveStartPushRequest.class);
        String videoId = req.videoId();
        String url = req.url();
        int urlType = req.urlType();
        int quality = req.videoQuality();

        // 幂等：先移除已存在的同 videoId 记录，再添加，保证 video_id 唯一
        // 新推流默认 camera_position=0（舱内）
        activeStreams.removeIf(s -> s.videoId().equals(videoId));
        pushRecords.removeIf(r -> r.videoId().equals(videoId));
        LiveStream stream = new LiveStream(videoId, url, urlType, quality, DEFAULT_CAMERA_POSITION, videoType);
        activeStreams.add(stream);

        // 尝试真实推流（RTMP 或 WebRTC），仅协议模拟时返回 513013（TC-LIVE-022）
        PushAttempt attempt = tryStartPush(videoId, url, urlType);
        long now = System.currentTimeMillis();
        if (!attempt.started()) {
            // 推流未真实启动：从 activeStreams 移除，避免后续 stop_push 误处理
            activeStreams.removeIf(s -> s.videoId().equals(videoId));
            // 推流记录保留失败原因，供前端排错（TC-LIVE-023）
            addPushRecord(new PushRecord(videoId, url, urlType, urlType, url, quality, videoType,
                    STATUS_FAILED, attempt.failReason(), now, null));
            log.info("仅协议模拟，返回 result=513013: videoId={}, urlType={}, reason={}", videoId, urlType, attempt.failReason());
            return Map.of("result", 513013);
        }
        // 成功记录实际推流地址（容错/降级时与下发地址不同，TC-LIVE-024）
        addPushRecord(new PushRecord(videoId, url, urlType, attempt.actualUrlType(), attempt.actualUrl(),
                quality, videoType, STATUS_PUSHING, null, now, null));
        log.info("直播推流已启动: videoId={}, urlType={}, quality={}", videoId, urlType, quality);
        return Map.of("result", 0);
    }

    /**
     * 尝试启动真实推流。
     * <p>url_type=1 (RTMP) 或 url_type=4 (WebRTC) 且 ffmpeg 支持对应协议时启动；
     * 否则降级为协议模拟（记诊断日志）。</p>
     * <p><b>WHIP 降级 RTMP</b>：url_type=4 (WebRTC) 但 ffmpeg 不支持 WHIP 时，
     * 若 ffmpeg 支持 RTMP，自动将 WebRTC URL 转换为 RTMP URL 推流（ZLM 做 RTMP→WebRTC 转换）。
     * 此降级行为为模拟器推断（DJI 文档未规定），记录 M-2 诊断日志待真机验证。</p>
     *
     * @return 推流尝试结果（started + 实际推流地址/类型 + 失败原因，供推流记录使用，TC-LIVE-023/024）
     */
    private PushAttempt tryStartPush(String videoId, String url, int urlType) {
        // 容错：url_type 非 RTMP/WebRTC 但 URL 以 rtmp:// 开头时，自动按 RTMP 推流
        // 场景：平台配置 url_type 与 url 协议不匹配（如 url_type=3 GB28181 + url=rtmp://...）
        if (urlType != FfmpegWhipPusher.URL_TYPE_RTMP
                && urlType != FfmpegWhipPusher.URL_TYPE_WEBRTC) {
            if (url != null && url.toLowerCase().startsWith("rtmp://")) {
                log.info("url_type={} 与 URL 协议(rtmp://)不匹配，自动按 RTMP 推流: videoId={}", urlType, videoId);
                diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, ServiceMethod.LIVE_START_PUSH.methodName(),
                        "url_type=" + urlType + " 与 URL 协议(rtmp://)不匹配，自动按 RTMP 推流。"
                        + "待真机验证：真机收到不匹配的 url_type 时是否也会容错处理。");
                urlType = FfmpegWhipPusher.URL_TYPE_RTMP;
            } else {
                // Agora/GB28181 不真实推流，仅协议模拟
                if (ffmpegPusher.isRealPushEnabled()) {
                    log.info("url_type={} 非 RTMP/WebRTC，仅协议模拟（不支持真实推流）", urlType);
                }
                return new PushAttempt(false, urlType, url,
                        "url_type=" + urlType + " 非 RTMP/WebRTC，不支持真实推流");
            }
        }

        // 实际推流的 urlType 和 url（可能因 WHIP 不支持而降级为 RTMP）
        int actualUrlType = urlType;
        String actualUrl = url;

        // WHIP 降级 RTMP：url_type=4 但 ffmpeg 不支持 WHIP 时，自动转换为 RTMP 推流
        if (urlType == FfmpegWhipPusher.URL_TYPE_WEBRTC && !ffmpegPusher.isPushAvailable(FfmpegWhipPusher.URL_TYPE_WEBRTC)) {
            if (ffmpegPusher.isPushAvailable(FfmpegWhipPusher.URL_TYPE_RTMP)) {
                // ffmpeg 支持 RTMP，将 WebRTC URL 转换为 RTMP URL
                String rtmpUrl = convertWhipToRtmp(url);
                if (rtmpUrl != null) {
                    log.info("ffmpeg 不支持 WHIP，自动降级为 RTMP 推流: videoId={}, rtmpUrl={}", videoId, rtmpUrl);
                    diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, ServiceMethod.LIVE_START_PUSH.methodName(),
                            "ffmpeg 不支持 WHIP，自动降级为 RTMP 推流。originalUrl=" + url + ", rtmpUrl=" + rtmpUrl
                            + "。待真机验证：真机不支持 WHIP 时是否也会降级为 RTMP，或直接报错。");
                    actualUrlType = FfmpegWhipPusher.URL_TYPE_RTMP;
                    actualUrl = rtmpUrl;
                } else {
                    log.warn("ffmpeg 不支持 WHIP，且 WebRTC URL 转 RTMP URL 失败，降级为协议模拟: videoId={}", videoId);
                    return new PushAttempt(false, urlType, url, "WebRTC URL 转 RTMP URL 失败");
                }
            } else {
                // ffmpeg 既不支持 WHIP 也不支持 RTMP，降级为协议模拟
                log.debug("ffmpeg 不支持 WHIP 和 RTMP，videoId={} 仅协议模拟", videoId);
                return new PushAttempt(false, urlType, url, "ffmpeg 不支持 WHIP 与 RTMP");
            }
        }

        if (!ffmpegPusher.isPushAvailable(actualUrlType)) {
            String proto = (actualUrlType == FfmpegWhipPusher.URL_TYPE_RTMP) ? "RTMP" : "WHIP";
            log.debug("ffmpeg 不支持 {}，videoId={} 仅协议模拟", proto, videoId);
            return new PushAttempt(false, actualUrlType, actualUrl, "ffmpeg 不支持 " + proto);
        }
        String videoFile = resolveVideoFile(videoId);
        boolean started = ffmpegPusher.startPush(videoId, actualUrl, videoFile, actualUrlType);
        if (started) {
            String proto = (actualUrlType == FfmpegWhipPusher.URL_TYPE_RTMP) ? "RTMP" : "WHIP";
            if (actualUrlType != urlType) {
                log.info("{} 真实推流已启动（从 WHIP 降级）: videoId={}", proto, videoId);
            } else {
                log.info("{} 真实推流已启动: videoId={}", proto, videoId);
            }
            return new PushAttempt(true, actualUrlType, actualUrl, null);
        } else {
            String proto = (actualUrlType == FfmpegWhipPusher.URL_TYPE_RTMP) ? "RTMP" : "WHIP";
            log.warn("{} 真实推流启动失败，降级为协议模拟: videoId={}", proto, videoId);
            return new PushAttempt(false, actualUrlType, actualUrl, "ffmpeg 推流进程启动失败（详见后端日志/诊断日志）");
        }
    }

    /**
     * 将 WebRTC (WHIP) URL 转换为 RTMP URL（WHIP 降级 RTMP 推流时使用）。
     * <p>解析 WebRTC URL 中的 host、app、stream 参数，构建 RTMP URL。</p>
     * <p>示例：
     * <pre>
     * 输入: http://172.30.48.1:3001/stream/index/api/webrtc?app=live&stream=xxx&type=push
     * 输出: rtmp://172.30.48.1:1935/live/xxx
     * </pre>
     * @param whipUrl WebRTC 推流 URL
     * @return RTMP URL，解析失败返回 null
     */
    private String convertWhipToRtmp(String whipUrl) {
        try {
            java.net.URI uri = java.net.URI.create(whipUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                log.warn("WebRTC URL 解析 host 失败: {}", whipUrl);
                return null;
            }
            // 从 query 参数中提取 app 和 stream
            String query = uri.getQuery();
            String app = "live";    // 默认 app
            String stream = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        if ("app".equals(kv[0])) app = kv[1];
                        if ("stream".equals(kv[0])) stream = kv[1];
                    }
                }
            }
            if (stream == null || stream.isBlank()) {
                log.warn("WebRTC URL 解析 stream 参数失败: {}", whipUrl);
                return null;
            }
            return String.format("rtmp://%s:1935/%s/%s", host, app, stream);
        } catch (Exception e) {
            log.warn("WebRTC URL 转 RTMP URL 失败: {} - {}", whipUrl, e.getMessage());
            return null;
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
        diagnosticRecorder.record(DiagnosticCode.SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED, ServiceMethod.LIVE_START_PUSH.methodName(),
                "视频文件不存在: " + primaryFile + " 和 default.mp4");
        return "";
    }

    /**
     * 停止推流：移除推流记录并停止 ffmpeg 进程，回 result=0。
     * <p>推流记录状态更新为 stopped 并记录结束时间（TC-LIVE-025）。</p>
     */
    private Map<String, Object> handleStopPush(JsonNode data) {
        if (data != null && !data.isMissingNode()) {
            var req = MessageCodec.fromJson(data.toString(), LiveStopPushRequest.class);
            String videoId = req.videoId();
            activeStreams.removeIf(s -> s.videoId().equals(videoId));
            ffmpegPusher.stopPush(videoId);
            markPushRecordStopped(videoId);
            log.info("直播推流已停止: videoId={}", videoId);
        }
        return Map.of("result", 0);
    }

    /**
     * 设置清晰度：更新推流记录的 quality，回 result=0。
     * <p>注：ffmpeg 推流进程不动态调整码率（标记 TODO，需重启进程实现）。</p>
     */
    private Map<String, Object> handleSetQuality(JsonNode data) {
        if (data != null && !data.isMissingNode()) {
            var req = MessageCodec.fromJson(data.toString(), LiveSetQualityRequest.class);
            String videoId = req.videoId();
            int quality = req.videoQuality();
            // 使用索引遍历，避免 CopyOnWriteArrayList 在并发修改时 indexOf 返回 -1 导致 IOOBE
            for (int i = 0; i < activeStreams.size(); i++) {
                LiveStream stream = activeStreams.get(i);
                if (stream.videoId().equals(videoId)) {
                    activeStreams.set(i, new LiveStream(
                            videoId, stream.url(), stream.urlType(), quality, stream.cameraPosition(), stream.videoType()));
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
        DockModel dockType = runtimeConfig.getDockType();
        if (dockType == DockModel.DOCK1) {
            log.warn("Dock1 不支持 live_camera_change 指令，返回占位 result=0（不更新状态）");
            return Map.of("result", 0);
        }
        if (data == null || data.isMissingNode()) {
            return Map.of("result", 0);
        }
        var req = MessageCodec.fromJson(data.toString(), LiveCameraChangeRequest.class);
        String videoId = req.videoId();
        int cameraPosition = req.cameraPosition();
        for (int i = 0; i < activeStreams.size(); i++) {
            LiveStream stream = activeStreams.get(i);
            if (stream.videoId().equals(videoId)) {
                activeStreams.set(i, new LiveStream(
                        videoId, stream.url(), stream.urlType(), stream.quality(), cameraPosition, stream.videoType()));
                log.info("直播相机已切换: videoId={}, cameraPosition={}", videoId, cameraPosition);
                break;
            }
        }
        return Map.of("result", 0);
    }

    /**
     * 切换直播镜头（Service Topic）：解析 video_type（+ video_id），按 video_id 精准切换；无 video_id 时全局更新。
     * <p>Dock 模式：video_type ∈ ir/normal/wide/zoom，无 video_id，全局更新。</p>
     * <p>RC Plus/RC Pro：video_type ∈ normal/thermal/wide/zoom，含 video_id，按 video_id 精准切换。</p>
     * <p>RC Plus 2：走 DRC Topic {@code drc_live_lens_change}（payload_index + video_type），由 {@link #handleDrcLensChange} 处理。</p>
     * <p>核实依据：[Dock3 live.html] Data 仅含 video_type；[RC Plus/RC Pro live.md] Data 含 video_id + video_type；[RC Plus 2 live.html] drc_live_lens_change Data 含 payload_index + video_type。</p>
     */
    private Map<String, Object> handleLensChange(JsonNode data) {
        if (data != null && !data.isMissingNode()) {
            var req = MessageCodec.fromJson(data.toString(), LiveLensChangeRequest.class);
            String newVideoType = req.videoType();
            if (newVideoType != null && !newVideoType.isEmpty()) {
                String videoId = req.videoId() != null ? req.videoId() : "";
                if (!videoId.isEmpty()) {
                    // RC Plus/RC Pro：按 video_id 精准切换
                    for (int i = 0; i < activeStreams.size(); i++) {
                        LiveStream stream = activeStreams.get(i);
                        if (stream.videoId().equals(videoId)) {
                            activeStreams.set(i, new LiveStream(
                                    videoId, stream.url(), stream.urlType(), stream.quality(),
                                    stream.cameraPosition(), newVideoType));
                            break;
                        }
                    }
                    log.info("直播镜头已切换: videoId={}, videoType={}", videoId, newVideoType);
                } else {
                    // Dock 模式：无 video_id，全局更新
                    this.videoType = newVideoType;
                    log.info("直播镜头已切换（全局）: videoType={}", newVideoType);
                }
                // TODO: 若需真实推流反映镜头切换，需重启 ffmpeg 进程使用新视频文件
            }
        }
        return Map.of("result", 0);
    }

    /**
     * 切换直播镜头（RC Plus 2，DRC Topic）：解析 payload_index + video_type 全局更新，回 result=0。
     * <p>DJI 枚举：video_type ∈ thermal/wide/zoom（无 normal）。含 payload_index（相机枚举值，格式 {type-subtype-gimbalindex}）。</p>
     * <p>核实依据：[RC Plus 2 live.html] drc_live_lens_change 走 DRC Topic（drc/down → drc/up），Data 含 payload_index + video_type。</p>
     * @param data DRC 指令 data，含 payload_index 和 video_type
     * @return DRC 回复 data（{result: 0}）
     */
    private Map<String, Object> handleDrcLensChange(JsonNode data) {
        if (data != null) {
            String payloadIndex = data.path("payload_index").asText();
            String newVideoType = data.path("video_type").asText();
            if (!newVideoType.isEmpty()) {
                this.videoType = newVideoType;
                log.info("Pilot 直播镜头已切换: payload_index={}, videoType={}", payloadIndex, newVideoType);
            }
        }
        return Map.of("result", 0);
    }

    /**
     * 停止所有直播推流（设备下线/关机时调用，TC-LIVE-021）。
     * <p>清空推流记录并停止全部 ffmpeg 推流进程（含 WHIP url_type=4 与 RTMP url_type=1），防止进程与带宽泄漏。
     * 对齐真实机场行为：关机断电后推流物理停止。幂等，重复调用无副作用。</p>
     * <p>推流记录（TC-LIVE-025）：所有 pushing 状态更新为 stopped（记录保留，供下线后排查）。</p>
     */
    public void stopAllStreams() {
        if (!activeStreams.isEmpty()) {
            log.info("设备下线，停止 {} 路直播推流", activeStreams.size());
            activeStreams.clear();
        }
        markAllPushRecordsStopped();
        ffmpegPusher.stopAll();
    }

    /**
     * 获取当前活跃推流列表（供 Web 控制台使用）。
     * <p>每条记录包含 video_id/url/url_type/quality/camera_position/video_type；全局 video_type（Dock 模式无 video_id 时）通过 {@link #getVideoType()} 获取。</p>
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
            m.put("video_type", s.videoType());
            result.add(m);
        }
        return result;
    }

    /** 获取全局镜头类型（live_lens_change 设置）。 */
    public String getVideoType() {
        return videoType;
    }

    /**
     * 获取推流记录列表（TC-LIVE-023~025，供直播推流面板展示）。
     * <p>最新在前，含失败记录（status=failed + fail_reason）；
     * 容错/降级场景 actual_url 与下发 url 不同（TC-LIVE-024）。</p>
     * <p>每条记录：video_id/url/url_type（平台下发）、actual_url/actual_url_type（实际推流）、
     * quality/video_type、status（pushing/stopped/failed）、fail_reason（仅失败）、start_time/end_time（epoch ms，未结束为 null）。</p>
     */
    public List<Map<String, Object>> getPushRecords() {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (PushRecord r : pushRecords) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("video_id", r.videoId());
            m.put("url", r.url());
            m.put("url_type", r.urlType());
            m.put("actual_url", r.actualUrl());
            m.put("actual_url_type", r.actualUrlType());
            m.put("quality", r.quality());
            m.put("video_type", r.videoType());
            m.put("status", r.status());
            if (r.failReason() != null) {
                m.put("fail_reason", r.failReason());
            }
            m.put("start_time", r.startTime());
            m.put("end_time", r.endTime());
            result.add(m);
        }
        return result;
    }

    /**
     * 新增推流记录（头部插入，最新在前），超出上限时淘汰最旧记录（TC-LIVE-025）。
     */
    private void addPushRecord(PushRecord record) {
        pushRecords.add(0, record);
        while (pushRecords.size() > MAX_PUSH_RECORDS) {
            pushRecords.remove(pushRecords.size() - 1);
        }
    }

    /**
     * 将指定 video_id 的 pushing 记录更新为 stopped 并记录结束时间（live_stop_push，TC-LIVE-025）。
     */
    private void markPushRecordStopped(String videoId) {
        for (int i = 0; i < pushRecords.size(); i++) {
            PushRecord r = pushRecords.get(i);
            if (r.videoId().equals(videoId) && STATUS_PUSHING.equals(r.status())) {
                pushRecords.set(i, new PushRecord(r.videoId(), r.url(), r.urlType(), r.actualUrlType(),
                        r.actualUrl(), r.quality(), r.videoType(), STATUS_STOPPED, r.failReason(),
                        r.startTime(), System.currentTimeMillis()));
                break;
            }
        }
    }

    /**
     * 将所有 pushing 记录更新为 stopped（设备下线，TC-LIVE-025）。
     */
    private void markAllPushRecordsStopped() {
        for (int i = 0; i < pushRecords.size(); i++) {
            PushRecord r = pushRecords.get(i);
            if (STATUS_PUSHING.equals(r.status())) {
                pushRecords.set(i, new PushRecord(r.videoId(), r.url(), r.urlType(), r.actualUrlType(),
                        r.actualUrl(), r.quality(), r.videoType(), STATUS_STOPPED, r.failReason(),
                        r.startTime(), System.currentTimeMillis()));
            }
        }
    }

    /** 直播流记录 */
    private record LiveStream(String videoId, String url, int urlType, int quality, int cameraPosition, String videoType) {}

    /**
     * 推流记录（TC-LIVE-023~025）：平台下发地址 + 实际推流地址 + 状态 + 失败原因。
     *
     * @param videoId      推流唯一标识
     * @param url          平台下发地址（live_start_push.url）
     * @param urlType      平台下发 url_type
     * @param actualUrlType 实际推流 url_type（容错/降级时与下发不同）
     * @param actualUrl    实际推流地址（容错/降级时与下发不同，TC-LIVE-024）
     * @param quality      清晰度
     * @param videoType    镜头类型
     * @param status       pushing/stopped/failed
     * @param failReason   失败原因（仅 failed 时非 null）
     * @param startTime    开始时间（epoch ms）
     * @param endTime      结束时间（epoch ms，未结束为 null）
     */
    private record PushRecord(String videoId, String url, int urlType,
                              int actualUrlType, String actualUrl,
                              int quality, String videoType,
                              String status, String failReason,
                              long startTime, Long endTime) {}

    /** 真实推流尝试结果（TC-LIVE-023/024）：是否启动 + 实际推流地址/类型 + 失败原因 */
    private record PushAttempt(boolean started, int actualUrlType, String actualUrl, String failReason) {}
}
