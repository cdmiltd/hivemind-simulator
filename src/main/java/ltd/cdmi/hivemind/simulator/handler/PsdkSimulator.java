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
import ltd.cdmi.dji.cloudapi.sdk.codec.MessageCodec;
import ltd.cdmi.dji.cloudapi.sdk.command.service.psdk.CustomDataTransmissionToPsdkRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.psdk.PsdkInputBoxTextSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.psdk.PsdkWidgetValueSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.psdk.SpeakerAudioPlayStartRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.psdk.SpeakerPlayModeSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.psdk.SpeakerPlayStopRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.psdk.SpeakerPlayVolumeSetRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.psdk.SpeakerReplayRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.psdk.SpeakerTtsPlayStartRequest;
import ltd.cdmi.dji.cloudapi.sdk.protocol.envelope.EventEnvelope;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.EventMethod;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.media.MediaUploader;
import ltd.cdmi.hivemind.simulator.media.StorageConfig;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PSDK 喊话器与负载事件模拟器（Dock1/Dock2/Dock3）。
 * <p>协议参考：DJI Cloud API PSDK 喊话器、浮窗文本、UI 资源包（Topic=thing/product/{gateway_sn}/events
 * 与 thing/product/{gateway_sn}/services）。</p>
 *
 * <p>覆盖 3 个同步 Service 指令（services_reply 仅含 result=0）：
 * <ul>
 *   <li>speaker_play_volume_set：设置喊话器音量，记录到内部状态</li>
 *   <li>speaker_play_mode_set：设置喊话器播放模式（0=单次/1=循环），记录到内部状态</li>
 *   <li>speaker_play_stop：停止播放，将播放状态置为 false</li>
 * </ul>
 *
 * <p>覆盖第 2/3 部分 4 个同步 Service 指令（services_reply 仅含 result=0）：
 * <ul>
 *   <li>speaker_replay：重新播放，将播放状态置为 true</li>
 *   <li>speaker_tts_play_start：开始播放 TTS 文本，记录 tts{name,text,md5} 并将播放状态置为 true</li>
 *   <li>speaker_audio_play_start：开始播放音频，记录 file{name,url,md5,format} 并将播放状态置为 true</li>
 *   <li>psdk_input_box_text_set：发送文本框内容，记录 value 并自动触发 psdk_floating_window_text 事件</li>
 *   <li>psdk_widget_value_set：设置控件值，记录到内部状态（psdk_index + widget_index 二维维护）</li>
 * </ul>
 *
 * <p>PSDK 互联互通（custom_data_transmission）：
 * <ul>
 *   <li>custom_data_transmission_to_psdk（Service 下行）：cloud→PSDK 自定义消息，记录 value，返回 result=0</li>
 *   <li>custom_data_transmission_from_psdk（Event 上行）：PSDK→cloud 自定义消息，need_reply=0（DJI 文档未标注，记录 M-2 诊断日志）</li>
 * </ul>
 *
 * <p>PSDK UI 资源完整上传流程（通过 REST API 触发）：
 * storage_config_get(module=1) 获取 STS 凭证 → 上传内置占位 UI 资源文件到对象存储 → 上报 psdk_ui_resource_upload_result 事件</p>
 *
 * <p>覆盖 4 个 Event 上报（通过 REST API 手动触发）：
 * <ul>
 *   <li>speaker_tts_play_start_progress：TTS 播放进度通知，data 含 result/output（output 包裹 psdk_index/status/md5/progress）</li>
 *   <li>speaker_audio_play_start_progress：音频播放进度通知，结构同 TTS，step_key 额外支持 download/encoding</li>
 *   <li>psdk_floating_window_text：浮窗文本推送，data 直接平铺 psdk_index + value（非 output 包裹）</li>
 *   <li>psdk_ui_resource_upload_result：UI 资源包上传结果，data 直接平铺 psdk_index/object_key/size/result</li>
 * </ul>
 *
 * <p>内置默认 TTS 文本和默认 PCM 字节，启动时预计算 MD5。REST API 可传入自定义 md5 覆盖（不实际接收文件，仅模拟）。
 * 平台喊话后，模拟器页面可直接播放内置音频文件用于本地验证。</p>
 *
 * <p><b>协议推断点（M-2 诊断日志）</b>：speaker_tts_play_start_progress 的 status 字段，
 * DJI 协议约束定义 {@code {"in_progress":"处理中","ok":"播放成功"}}，但 Example 显示 {@code "status": "success"}
 * （不在枚举内，DJI 文档自身不一致）；而 speaker_audio_play_start_progress Example 显示 {@code "status": "in_progress"}
 * （在枚举内）。参考：DRC 通道 drc_speaker_play_progress 的 status 枚举为 {@code {failed, in_progress, success}}，
 * 与 PSDK events 通道约束不同。模拟器遵循 PSDK events 约束定义使用 {@code in_progress}/{@code ok}，
 * 记录 M-2 诊断日志（MONITOR_SIMULATOR_INFERENCE），待真机验证。</p>
 *
 * <p>核实依据：[Dock1/Dock2/Dock3 wayline.html] PSDK 喊话器、psdk 浮窗文本、psdk UI 资源包</p>
 */
@Component
public class PsdkSimulator {

    private static final Logger log = LoggerFactory.getLogger(PsdkSimulator.class);

    /** PSDK 同步 Service 指令集 */
    private static final Set<String> PSDK_SERVICE_METHODS = Set.of(
            "speaker_play_volume_set", "speaker_play_mode_set", "speaker_play_stop",
            "speaker_replay", "speaker_tts_play_start", "speaker_audio_play_start",
            "psdk_input_box_text_set", "psdk_widget_value_set",
            "custom_data_transmission_to_psdk"
    );

    /** 内置默认 TTS 文本（用于预计算 md5，模拟器不实际进行 TTS 合成） */
    private static final String DEFAULT_TTS_TEXT = "模拟器TTS测试喊话内容";

    /** 内置默认音频字节（占位 PCM，用于预计算 md5，模拟器不实际播放此原始字节） */
    private static final byte[] DEFAULT_AUDIO_BYTES = buildDefaultAudioBytes();

    /** 内置默认 TTS 文本的 MD5（启动时预计算） */
    private static final String DEFAULT_TTS_MD5 = md5Hex(DEFAULT_TTS_TEXT.getBytes(StandardCharsets.UTF_8));

    /** 内置默认音频字节的 MD5（启动时预计算） */
    private static final String DEFAULT_AUDIO_MD5 = md5Hex(DEFAULT_AUDIO_BYTES);

    /** 内置占位 UI 资源字节（一段固定的 widget JSON，用于 PSDK UI 资源上传流程） */
    private static final byte[] DEFAULT_UI_RESOURCE_BYTES =
            "{\"version\":\"1.0\",\"widgets\":[{\"type\":\"text\",\"x\":0,\"y\":0,\"w\":100,\"h\":30}]}"
                    .getBytes(StandardCharsets.UTF_8);

    private final MqttClientManager mqtt;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final DockOnlineService onlineService;
    private final MediaUploader mediaUploader;
    private final DockTopicSchema dockTopicSchema;

    /** 喊话器音量状态：psdk_index → volume（0~100），默认 50 */
    private final ConcurrentHashMap<Integer, Integer> speakerVolume = new ConcurrentHashMap<>();

    /** 喊话器播放模式：psdk_index → play_mode（0=单次/1=循环），默认 0 */
    private final ConcurrentHashMap<Integer, Integer> speakerPlayMode = new ConcurrentHashMap<>();

    /** 喊话器播放状态：psdk_index → 是否播放中，默认 false */
    private final ConcurrentHashMap<Integer, Boolean> speakerPlaying = new ConcurrentHashMap<>();

    /** 最近一次 TTS 播放信息：psdk_index → {name, text, md5} */
    private final ConcurrentHashMap<Integer, Map<String, String>> lastTts = new ConcurrentHashMap<>();

    /** 最近一次音频播放文件信息：psdk_index → {name, url, md5, format} */
    private final ConcurrentHashMap<Integer, Map<String, String>> lastAudioFile = new ConcurrentHashMap<>();

    /** 文本框内容：psdk_index → value */
    private final ConcurrentHashMap<Integer, String> inputBoxText = new ConcurrentHashMap<>();

    /** 控件值：psdk_index → {widget_index → value} */
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Integer>> widgetValues = new ConcurrentHashMap<>();

    /** 最近一次 cloud→PSDK 自定义消息内容 */
    private volatile String lastCustomData;

    public PsdkSimulator(MqttClientManager mqtt, RuntimeConfig runtimeConfig,
                         DiagnosticLogRecorder diagnosticRecorder,
                         DockOnlineService onlineService, MediaUploader mediaUploader,
                         DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
        this.onlineService = onlineService;
        this.mediaUploader = mediaUploader;
        this.dockTopicSchema = dockTopicSchema;

        // M-2 诊断日志：speaker_tts_play_start_progress status 字段 DJI 文档自身不一致的推断
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                "speaker_tts_play_start_progress",
                "DJI 协议约束 status 枚举 {in_progress, ok}，但 speaker_tts_play_start_progress Example 显示 'success'（不在枚举内），"
                        + "而 speaker_audio_play_start_progress Example 显示 'in_progress'（在枚举内）。"
                        + "参考：DRC 通道 drc_speaker_play_progress 的 status 枚举为 {failed, in_progress, success}，与 PSDK events 通道约束不同。"
                        + "模拟器遵循 PSDK events 约束定义使用 in_progress/ok，待真机验证。");

        // M-2 诊断日志：custom_data_transmission_from_psdk need_reply 值未在 DJI 文档中标注
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                "custom_data_transmission_from_psdk",
                "DJI psdk-transmit-custom-data.html 文档未标注 need_reply 值。"
                        + "模拟器遵循现有 PSDK 事件设置使用 need_reply=0（单向通知），待真机验证。");
    }

    /**
     * 判断是否为 PSDK 同步 Service 指令。
     *
     * @param method services method
     * @return true 表示是 PSDK 服务指令
     */
    public static boolean isPsdkServiceMethod(String method) {
        return PSDK_SERVICE_METHODS.contains(method);
    }

    // ==================== Service 处理（services_reply 仅含 result=0） ====================

    /**
     * 路由 PSDK 服务指令。
     *
     * @param method services method
     * @param data 指令 data
     * @return services_reply 的 output（仅含 result=0）
     */
    public Map<String, Object> handleService(String method, JsonNode data) {
        return switch (method) {
            case "speaker_play_volume_set" -> handleVolumeSet(data);
            case "speaker_play_mode_set" -> handleModeSet(data);
            case "speaker_play_stop" -> handlePlayStop(data);
            case "speaker_replay" -> handleReplay(data);
            case "speaker_tts_play_start" -> handleTtsPlayStart(data);
            case "speaker_audio_play_start" -> handleAudioPlayStart(data);
            case "psdk_input_box_text_set" -> handleInputBoxTextSet(data);
            case "psdk_widget_value_set" -> handleWidgetValueSet(data);
            case "custom_data_transmission_to_psdk" -> handleCustomDataToPsdk(data);
            default -> throw new IllegalArgumentException("Unsupported PSDK service method: " + method);
        };
    }

    /** speaker_play_volume_set：记录音量到内部状态，返回 result=0 */
    private Map<String, Object> handleVolumeSet(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), SpeakerPlayVolumeSetRequest.class);
        int psdkIndex = req.psdkIndex();
        int volume = req.playVolume();
        speakerVolume.put(psdkIndex, volume);
        log.info("speaker_play_volume_set: psdk_index={}, play_volume={}", psdkIndex, volume);
        return Map.of("result", 0);
    }

    /** speaker_play_mode_set：记录播放模式到内部状态，返回 result=0 */
    private Map<String, Object> handleModeSet(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), SpeakerPlayModeSetRequest.class);
        int psdkIndex = req.psdkIndex();
        int playMode = req.playMode();
        speakerPlayMode.put(psdkIndex, playMode);
        log.info("speaker_play_mode_set: psdk_index={}, play_mode={}", psdkIndex, playMode);
        return Map.of("result", 0);
    }

    /** speaker_play_stop：将播放状态置为 false，返回 result=0 */
    private Map<String, Object> handlePlayStop(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), SpeakerPlayStopRequest.class);
        int psdkIndex = req.psdkIndex();
        speakerPlaying.put(psdkIndex, false);
        log.info("speaker_play_stop: psdk_index={}", psdkIndex);
        return Map.of("result", 0);
    }

    /** speaker_replay：将播放状态置为 true（重新播放），返回 result=0 */
    private Map<String, Object> handleReplay(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), SpeakerReplayRequest.class);
        int psdkIndex = req.psdkIndex();
        speakerPlaying.put(psdkIndex, true);
        log.info("speaker_replay: psdk_index={}", psdkIndex);
        return Map.of("result", 0);
    }

    /** speaker_tts_play_start：记录 tts 信息并将播放状态置为 true，返回 result=0 */
    private Map<String, Object> handleTtsPlayStart(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), SpeakerTtsPlayStartRequest.class);
        int psdkIndex = req.psdkIndex();
        Map<String, String> ttsInfo = new LinkedHashMap<>();
        ttsInfo.put("name", req.tts().name());
        ttsInfo.put("text", req.tts().text());
        ttsInfo.put("md5", req.tts().md5());
        lastTts.put(psdkIndex, ttsInfo);
        speakerPlaying.put(psdkIndex, true);
        log.info("speaker_tts_play_start: psdk_index={}, tts.name={}, tts.md5={}",
                psdkIndex, ttsInfo.get("name"), ttsInfo.get("md5"));
        return Map.of("result", 0);
    }

    /** speaker_audio_play_start：记录音频文件信息并将播放状态置为 true，返回 result=0 */
    private Map<String, Object> handleAudioPlayStart(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), SpeakerAudioPlayStartRequest.class);
        int psdkIndex = req.psdkIndex();
        Map<String, String> fileInfo = new LinkedHashMap<>();
        fileInfo.put("name", req.file().name());
        fileInfo.put("url", req.file().url());
        fileInfo.put("md5", req.file().md5());
        fileInfo.put("format", req.file().format());
        lastAudioFile.put(psdkIndex, fileInfo);
        speakerPlaying.put(psdkIndex, true);
        log.info("speaker_audio_play_start: psdk_index={}, file.name={}, file.format={}",
                psdkIndex, fileInfo.get("name"), fileInfo.get("format"));
        return Map.of("result", 0);
    }

    /**
     * psdk_input_box_text_set：记录文本框内容并自动触发 psdk_floating_window_text 事件，返回 result=0。
     * <p>输入框内容同步推送到浮窗（services 应答 + events 上报联动）。</p>
     */
    private Map<String, Object> handleInputBoxTextSet(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), PsdkInputBoxTextSetRequest.class);
        int psdkIndex = req.psdkIndex();
        String value = req.value();
        inputBoxText.put(psdkIndex, value);
        log.info("psdk_input_box_text_set: psdk_index={}, value={}", psdkIndex, value);

        // 自动触发 psdk_floating_window_text 事件（将输入框内容同步推送到浮窗）
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("psdk_index", psdkIndex);
        eventData.put("value", value);
        publishEvent(EventMethod.PSDK_FLOATING_WINDOW_TEXT, eventData);

        return Map.of("result", 0);
    }

    /** psdk_widget_value_set：记录控件值到内部状态，返回 result=0 */
    private Map<String, Object> handleWidgetValueSet(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), PsdkWidgetValueSetRequest.class);
        int psdkIndex = req.psdkIndex();
        int widgetIndex = req.index();
        int value = req.value();
        widgetValues.computeIfAbsent(psdkIndex, k -> new ConcurrentHashMap<>()).put(widgetIndex, value);
        log.info("psdk_widget_value_set: psdk_index={}, widget_index={}, value={}", psdkIndex, widgetIndex, value);
        return Map.of("result", 0);
    }

    /** custom_data_transmission_to_psdk：记录 cloud→PSDK 自定义消息内容，返回 result=0 */
    private Map<String, Object> handleCustomDataToPsdk(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), CustomDataTransmissionToPsdkRequest.class);
        String value = req.value();
        lastCustomData = value;
        log.info("custom_data_transmission_to_psdk: value={}", value);
        return Map.of("result", 0);
    }

    // ==================== Event 触发（通过 REST API 手动触发） ====================

    /**
     * 触发 speaker_tts_play_start_progress 事件（TTS 播放进度通知）。
     * <p>data 结构：{@code {result: 0, output: {psdk_index, status, md5, progress: {percent, step_key}}}}</p>
     *
     * @param psdkIndex PSDK 负载设备索引（必填）
     * @param status 当前阶段：in_progress / ok
     * @param percent 进度百分比（0~100）
     * @param stepKey 当前步骤：change_work_mode / play / upload
     * @param md5Override 自定义 md5（覆盖内置默认 TTS 文本 MD5），可为 null
     * @return 触发结果
     */
    public TriggerResult triggerTtsPlayProgress(int psdkIndex, String status, int percent,
                                                String stepKey, String md5Override) {
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报 PSDK 事件");
        }
        String md5 = md5Override != null && !md5Override.isEmpty() ? md5Override : DEFAULT_TTS_MD5;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("psdk_index", psdkIndex);
        output.put("status", status);
        output.put("md5", md5);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("percent", percent);
        progress.put("step_key", stepKey);
        output.put("progress", progress);

        publishEvent(EventMethod.SPEAKER_TTS_PLAY_START_PROGRESS, Map.of("result", 0, "output", output));
        log.info("speaker_tts_play_start_progress 已上报: psdk_index={}, status={}, percent={}, step_key={}, md5={}",
                psdkIndex, status, percent, stepKey, md5);
        return TriggerResult.ok();
    }

    /**
     * 触发 speaker_audio_play_start_progress 事件（音频播放进度通知）。
     * <p>data 结构同 TTS，step_key 额外支持 download / encoding。</p>
     *
     * @param psdkIndex PSDK 负载设备索引（必填）
     * @param status 当前阶段：in_progress / ok
     * @param percent 进度百分比（0~100）
     * @param stepKey 当前步骤：change_work_mode / download / encoding / play / upload
     * @param md5Override 自定义 md5（覆盖内置默认音频字节 MD5），可为 null
     * @return 触发结果
     */
    public TriggerResult triggerAudioPlayProgress(int psdkIndex, String status, int percent,
                                                  String stepKey, String md5Override) {
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报 PSDK 事件");
        }
        String md5 = md5Override != null && !md5Override.isEmpty() ? md5Override : DEFAULT_AUDIO_MD5;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("psdk_index", psdkIndex);
        output.put("status", status);
        output.put("md5", md5);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("percent", percent);
        progress.put("step_key", stepKey);
        output.put("progress", progress);

        publishEvent(EventMethod.SPEAKER_AUDIO_PLAY_START_PROGRESS, Map.of("result", 0, "output", output));
        log.info("speaker_audio_play_start_progress 已上报: psdk_index={}, status={}, percent={}, step_key={}, md5={}",
                psdkIndex, status, percent, stepKey, md5);
        return TriggerResult.ok();
    }

    /**
     * 触发 psdk_floating_window_text 事件（浮窗文本推送）。
     * <p>data 直接平铺 psdk_index + value（非 output 包裹）。</p>
     *
     * @param psdkIndex PSDK 负载设备索引（必填）
     * @param value 浮窗内容
     * @return 触发结果
     */
    public TriggerResult triggerFloatingWindowText(int psdkIndex, String value) {
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报 PSDK 事件");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("psdk_index", psdkIndex);
        data.put("value", value);

        publishEvent(EventMethod.PSDK_FLOATING_WINDOW_TEXT, data);
        log.info("psdk_floating_window_text 已上报: psdk_index={}, value={}", psdkIndex, value);
        return TriggerResult.ok();
    }

    /**
     * 触发 psdk_ui_resource_upload_result 事件（UI 资源包上传结果上报）。
     * <p>data 直接平铺 psdk_index + object_key + size + result（非 output 包裹）。</p>
     *
     * @param psdkIndex PSDK 负载设备索引（必填）
     * @param objectKey OSS 对象
     * @param size 文件大小（字节）
     * @param result 错误码（0=成功）
     * @return 触发结果
     */
    public TriggerResult triggerUiResourceUploadResult(int psdkIndex, String objectKey,
                                                       long size, int result) {
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报 PSDK 事件");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("psdk_index", psdkIndex);
        data.put("object_key", objectKey);
        data.put("size", size);
        data.put("result", result);

        publishEvent(EventMethod.PSDK_UI_RESOURCE_UPLOAD_RESULT, data);
        log.info("psdk_ui_resource_upload_result 已上报: psdk_index={}, object_key={}, size={}, result={}",
                psdkIndex, objectKey, size, result);
        return TriggerResult.ok();
    }

    /**
     * 触发 custom_data_transmission_from_psdk 事件（PSDK→cloud 自定义消息推送）。
     * <p>data 结构：{@code {value: text}}（length < 256）。
     * need_reply=0（DJI 文档未标注，遵循现有 PSDK 事件设置，记录 M-2 诊断日志）。</p>
     *
     * @param value 数据内容（长度 < 256）
     * @return 触发结果
     */
    public TriggerResult triggerCustomDataFromPsdk(String value) {
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报 PSDK 事件");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", value);
        publishEvent(EventMethod.CUSTOM_DATA_TRANSMISSION_FROM_PSDK, data);
        log.info("custom_data_transmission_from_psdk 已上报: value={}", value);
        return TriggerResult.ok();
    }

    /**
     * PSDK UI 资源完整上传流程：storage_config_get(module=1) → 上传内置占位文件 → 上报 psdk_ui_resource_upload_result 事件。
     * <p>通过 REST API 触发，验证平台 storage_config_get (module=1) 回复与 psdk_ui_resource_upload_result 事件。</p>
     *
     * @param psdkIndex PSDK 负载设备索引
     * @return TriggerResult 表示流程成功/失败
     */
    public TriggerResult uploadUiResource(int psdkIndex) {
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上传 PSDK UI 资源");
        }

        // 1. 发送 storage_config_get (module=1) 请求获取 STS 凭证
        log.info("PSDK UI 资源上传: 发送 storage_config_get(module=1), psdk_index={}", psdkIndex);
        JsonNode configReply = onlineService.sendRequest("storage_config_get", Map.of("module", 1));
        StorageConfig config = StorageConfig.fromReply(configReply);
        if (config == null || !config.isValid()) {
            log.warn("PSDK UI 资源上传: STS 凭证获取失败，降级为仅事件上报（result=1）");
            triggerUiResourceUploadResult(psdkIndex, "", 0, 1);
            return TriggerResult.fail("STORAGE_CONFIG_FAILED", "storage_config_get(module=1) 凭证获取失败");
        }

        // 2. 将内置占位 UI 资源字节写入临时文件并上传
        String objectKey = config.objectKeyPrefix() + "/" + psdkIndex + "/widget";
        int size = DEFAULT_UI_RESOURCE_BYTES.length;
        int uploadResult = 0;
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("psdk-ui-resource-", ".json");
            Files.write(tempFile, DEFAULT_UI_RESOURCE_BYTES);
            boolean uploaded = mediaUploader.upload(tempFile, config, objectKey);
            if (!uploaded) {
                uploadResult = 1;
                log.warn("PSDK UI 资源上传: 文件上传失败, object_key={}", objectKey);
            } else {
                log.info("PSDK UI 资源上传: 文件上传成功, object_key={}, size={}", objectKey, size);
            }
        } catch (Exception e) {
            uploadResult = 1;
            log.warn("PSDK UI 资源上传: 异常 - {}", e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }

        // 3. 上报 psdk_ui_resource_upload_result 事件
        triggerUiResourceUploadResult(psdkIndex, objectKey, size, uploadResult);
        return TriggerResult.ok();
    }

    // ==================== 事件发布 ====================

    /**
     * 发布事件到 thing/product/{sn}/events，need_reply=0（单向通知）。
     * <p>报文格式：{@code {bid, tid, timestamp, need_reply:0, gateway, method, data}}</p>
     */
    private void publishEvent(EventMethod method, Map<String, Object> data) {
        EventEnvelope envelope = EventEnvelope.of(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                method, data, runtimeConfig.getDockSn());

        String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getDockSn());
        mqtt.publish(topic, MessageCodec.toJson(envelope));
    }

    // ==================== REST API 辅助（状态查询与音频资源） ====================

    /**
     * 查询指定 psdk_index 的喊话器音量。
     */
    public int getSpeakerVolume(int psdkIndex) {
        return speakerVolume.getOrDefault(psdkIndex, 50);
    }

    /**
     * 查询指定 psdk_index 的喊话器播放模式。
     */
    public int getSpeakerPlayMode(int psdkIndex) {
        return speakerPlayMode.getOrDefault(psdkIndex, 0);
    }

    /**
     * 查询指定 psdk_index 的喊话器播放状态。
     */
    public boolean isSpeakerPlaying(int psdkIndex) {
        return speakerPlaying.getOrDefault(psdkIndex, false);
    }

    /**
     * 查询指定 psdk_index 最近一次 TTS 播放信息。
     * @return {name, text, md5} 或 null（未收到过 speaker_tts_play_start）
     */
    public Map<String, String> getLastTts(int psdkIndex) {
        return lastTts.get(psdkIndex);
    }

    /**
     * 查询指定 psdk_index 最近一次音频播放文件信息。
     * @return {name, url, md5, format} 或 null（未收到过 speaker_audio_play_start）
     */
    public Map<String, String> getLastAudioFile(int psdkIndex) {
        return lastAudioFile.get(psdkIndex);
    }

    /**
     * 查询指定 psdk_index 的文本框内容。
     * @return value 或 null（未收到过 psdk_input_box_text_set）
     */
    public String getInputBoxText(int psdkIndex) {
        return inputBoxText.get(psdkIndex);
    }

    /**
     * 查询指定 psdk_index + widget_index 的控件值。
     * @return value 或 null（未收到过 psdk_widget_value_set）
     */
    public Integer getWidgetValue(int psdkIndex, int widgetIndex) {
        ConcurrentHashMap<Integer, Integer> widgets = widgetValues.get(psdkIndex);
        return widgets == null ? null : widgets.get(widgetIndex);
    }

    /**
     * 查询指定 psdk_index 的所有控件值。
     * @return {widget_index → value} 或 null（未收到过 psdk_widget_value_set）
     */
    public Map<Integer, Integer> getWidgetValues(int psdkIndex) {
        return widgetValues.get(psdkIndex);
    }

    /**
     * 查询最近一次 cloud→PSDK 自定义消息内容。
     *
     * @return 自定义消息 value，未收到时为 null
     */
    public String getLastCustomData() {
        return lastCustomData;
    }

    /**
     * 获取内置默认 TTS 文本（供 UI 显示与播放）。
     */
    public String getDefaultTtsText() {
        return DEFAULT_TTS_TEXT;
    }

    /**
     * 获取内置默认 TTS 文本的 MD5。
     */
    public String getDefaultTtsMd5() {
        return DEFAULT_TTS_MD5;
    }

    /**
     * 获取内置默认音频字节的 MD5。
     */
    public String getDefaultAudioMd5() {
        return DEFAULT_AUDIO_MD5;
    }

    // ==================== 工具方法 ====================

    /**
     * 构造占位 PCM 音频字节（模拟一段 100ms 静音 PCM 16-bit 单声道 8kHz 数据）。
     * <p>用于预计算 MD5 与 UI 播放占位，不模拟真实音频内容。</p>
     */
    private static byte[] buildDefaultAudioBytes() {
        // 8000 samples/sec * 0.1 sec * 2 bytes/sample = 1600 bytes（全零，静音）
        return new byte[1600];
    }

    /**
     * 计算字节数组的 MD5（32 位小写 hex 字符串）。
     */
    private static String md5Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    /** 触发结果 */
    public record TriggerResult(boolean success, String code, String message) {
        public static TriggerResult ok() {
            return new TriggerResult(true, null, null);
        }
        public static TriggerResult fail(String code, String message) {
            return new TriggerResult(false, code, message);
        }
    }
}
