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
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.DrcUpMethod;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.DrcMessage;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 识别功能模拟器（Dock3 专属）。
 * <p>维护 AI 识别状态，处理 11 个 DRC 下行指令，每次状态变化后推送 {@code drc_ai_info_push} 到 drc/up。
 * <p>详见 DJI Cloud API <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/dock-feature-set/ai-target-recognition.html">AI 目标识别</a>。
 * <p>不模拟 SEI 识别结果注入视频流（技术不可行，模拟器推流是 ffmpeg 推本地文件）。
 */
@Component
public class AiSimulator {

    private static final Logger log = LoggerFactory.getLogger(AiSimulator.class);

    // ==================== DRC 下行指令 method 名（DJI Cloud API v1.16 新增） ====================
    public static final String DRC_AI_MODEL_SELECT = "drc_ai_model_select";
    public static final String DRC_AI_IDENTIFY_SET = "drc_ai_identify_set";
    public static final String DRC_AI_IDENTIFY_SCORE_MODE_SET = "drc_ai_identify_score_mode_set";
    public static final String DRC_AI_IDENTIFY_SCORE_SET = "drc_ai_identify_score_set";
    public static final String DRC_AI_IDENTIFY_SCORE_RESET = "drc_ai_identify_score_reset";
    public static final String DRC_AI_IDENTIFY_FILTER_SET = "drc_ai_identify_filter_set";
    public static final String DRC_AI_SPOTLIGHT_ZOOM_SET = "drc_ai_spotlight_zoom_set";
    public static final String DRC_AI_SPOTLIGHT_ZOOM_TRACK = "drc_ai_spotlight_zoom_track";
    public static final String DRC_AI_SPOTLIGHT_ZOOM_SELECT = "drc_ai_spotlight_zoom_select";
    public static final String DRC_AI_SPOTLIGHT_ZOOM_CONFIRM = "drc_ai_spotlight_zoom_confirm";
    public static final String DRC_AI_SPOTLIGHT_ZOOM_STOP = "drc_ai_spotlight_zoom_stop";

    // ==================== AI 状态字段 ====================
    /** AI 识别开关：0=关闭, 1=开启 */
    private volatile int identifyOn = 0;
    /** AI 跟随开关：0=关闭, 1=开启 */
    private volatile int spotlightZoomOn = 0;
    /** AI 跟随状态：0=空闲, 1=跟踪中, 2=框选等待确认 */
    private volatile int spotlightZoomState = 0;
    /** AI 跟随状态原因：0=正常 */
    private volatile int spotlightZoomStateReason = 0;
    /** 当前选中的 AI 模型 index */
    private volatile int selectedModelIndex = 0;
    /** 置信度模式：1=计数模式, 2=搜救模式, 3=自定义模式 */
    private volatile int scoreMode = 1;
    /** 置信度阈值（0-100，仅自定义模式有效） */
    private volatile int score = 100;
    /** 目标过滤列表（1=未知, 2=人, 3=车, 4=船），默认人/车/船 */
    private volatile List<Integer> filters = List.of(2, 3, 4);

    private final MqttClientManager mqtt;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    public AiSimulator(MqttClientManager mqtt, RuntimeConfig runtimeConfig,
                       DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
    }

    // ==================== 11 个 DRC 指令处理方法 ====================

    /** drc_ai_identify_set：AI 识别开关设置 */
    public Map<String, Object> handleAiIdentifySet(JsonNode data) {
        int on = data.path("identify_on").asInt(0);
        this.identifyOn = on;
        log.info("DRC AI 识别开关: identify_on={} (0=关闭, 1=开启)", on);
        pushAiInfo();
        return success();
    }

    /** drc_ai_model_select：AI 模型选择 */
    public Map<String, Object> handleAiModelSelect(JsonNode data) {
        int index = data.path("index").asInt(0);
        this.selectedModelIndex = index;
        log.info("DRC AI 模型选择: index={}", index);
        pushAiInfo();
        return success();
    }

    /** drc_ai_identify_score_mode_set：设置 AI 识别置信度模式 */
    public Map<String, Object> handleAiIdentifyScoreModeSet(JsonNode data) {
        int mode = data.path("score_mode").asInt(1);
        this.scoreMode = mode;
        log.info("DRC AI 置信度模式: score_mode={} (1=计数, 2=搜救, 3=自定义)", mode);
        pushAiInfo();
        return success();
    }

    /** drc_ai_identify_score_set：设置 AI 识别置信度 */
    public Map<String, Object> handleAiIdentifyScoreSet(JsonNode data) {
        int s = data.path("score").asInt(100);
        this.score = s;
        log.info("DRC AI 置信度阈值: score={}", s);
        pushAiInfo();
        return success();
    }

    /** drc_ai_identify_score_reset：重置 AI 识别置信度（恢复默认值 100） */
    public Map<String, Object> handleAiIdentifyScoreReset(JsonNode data) {
        this.score = 100;
        log.info("DRC AI 置信度重置: score 恢复默认值 100");
        pushAiInfo();
        return success();
    }

    /** drc_ai_identify_filter_set：设置 AI 识别目标过滤列表 */
    public Map<String, Object> handleAiIdentifyFilterSet(JsonNode data) {
        List<Integer> filterList = new ArrayList<>();
        JsonNode filtersNode = data.path("filters");
        if (filtersNode.isArray()) {
            filtersNode.forEach(n -> filterList.add(n.asInt()));
        }
        // 空列表=全部禁用（协议合法语义，识别空集），须接受并生效；
        // 仅当请求未携带 filters 字段（非数组）时保持原值
        if (filtersNode.isArray()) {
            this.filters = List.copyOf(filterList);
        }
        log.info("DRC AI 目标过滤列表: filters={}", this.filters);
        pushAiInfo();
        return success();
    }

    /** drc_ai_spotlight_zoom_set：AI 跟随开关设置 */
    public Map<String, Object> handleAiSpotlightZoomSet(JsonNode data) {
        int on = data.path("spotlight_zoom_on").asInt(0);
        this.spotlightZoomOn = on;
        log.info("DRC AI 跟随开关: spotlight_zoom_on={} (0=关闭, 1=开启)", on);
        pushAiInfo();
        return success();
    }

    /** drc_ai_spotlight_zoom_track：AI 识别目标跟随（按 target_index） */
    public Map<String, Object> handleAiSpotlightZoomTrack(JsonNode data) {
        int targetIndex = data.path("target_index").asInt(0);
        this.spotlightZoomState = 1;  // 1=跟踪中
        log.info("DRC AI 目标跟随: target_index={}, state=1(跟踪中)", targetIndex);
        pushAiInfo();
        return success();
    }

    /** drc_ai_spotlight_zoom_select：AI 框选目标跟随 */
    public Map<String, Object> handleAiSpotlightZoomSelect(JsonNode data) {
        this.spotlightZoomState = 2;  // 2=框选等待确认
        log.info("DRC AI 框选跟随: state=2(框选等待确认)");
        pushAiInfo();
        return success();
    }

    /** drc_ai_spotlight_zoom_confirm：AI 框选目标跟随确认 */
    public Map<String, Object> handleAiSpotlightZoomConfirm(JsonNode data) {
        this.spotlightZoomState = 1;  // 1=跟踪中
        log.info("DRC AI 框选确认: state=1(跟踪中)");
        pushAiInfo();
        return success();
    }

    /** drc_ai_spotlight_zoom_stop：停止目标跟随 */
    public Map<String, Object> handleAiSpotlightZoomStop(JsonNode data) {
        this.spotlightZoomState = 0;  // 0=空闲
        log.info("DRC AI 停止跟随: state=0(空闲)");
        pushAiInfo();
        return success();
    }

    // ==================== AI 状态推送 ====================

    /**
     * 推送 drc_ai_info_push 到 drc/up。
     * <p>每次 AI 状态变化后调用，让平台获知最新 AI 状态。
     */
    public void pushAiInfo() {
        Map<String, Object> aiInfo = buildAiInfo();
        String drcUpTopic = dockTopicSchema.topic(dockTopicSchema.drcUp(), runtimeConfig.getGatewaySn());
        mqtt.publishJson(drcUpTopic, DrcMessage.event(DrcUpMethod.DRC_AI_INFO_PUSH.methodName(), aiInfo));
        log.info("已推送 drc_ai_info_push: identify_on={}, spotlight_zoom_on={}, state={}",
                identifyOn, spotlightZoomOn, spotlightZoomState);
    }

    /**
     * 构造 AI 状态 Map（drc_ai_info_push 的 data）。
     * <p>供 {@link ltd.cdmi.hivemind.simulator.device.DeviceSimulator#publishPsdkAndAiEvents} 调用，
     * 确保定时推送与指令触发的推送使用同一数据源。
     */
    public Map<String, Object> buildAiInfo() {
        Map<String, Object> aiInfo = new LinkedHashMap<>();
        aiInfo.put("identify_on", identifyOn);
        aiInfo.put("spotlight_zoom_on", spotlightZoomOn);

        // ai_spotlight_zoom
        Map<String, Object> aiSpotlightZoom = new LinkedHashMap<>();
        aiSpotlightZoom.put("state", spotlightZoomState);
        aiSpotlightZoom.put("state_reason", spotlightZoomStateReason);
        aiInfo.put("ai_spotlight_zoom", aiSpotlightZoom);

        // ai_model_list（固定列表）
        List<Map<String, Object>> aiModelList = new ArrayList<>();
        Map<String, Object> model1 = new LinkedHashMap<>();
        model1.put("index", 0);
        model1.put("signed_name", "DJI");
        aiModelList.add(model1);
        aiInfo.put("ai_model_list", aiModelList);

        // selected_ai_model
        Map<String, Object> selectedModel = new LinkedHashMap<>();
        selectedModel.put("index", selectedModelIndex);
        selectedModel.put("score", score);
        selectedModel.put("score_mode", scoreMode);
        selectedModel.put("image_source", List.of(1, 2, 3));  // 广角/变焦/红外
        selectedModel.put("digital_effect", List.of(0, 1, 2));
        selectedModel.put("filters", filters);
        // labels（固定标签列表，对齐 DJI AI 目标识别：人/车/船）
        List<Map<String, Object>> labels = new ArrayList<>();
        Map<String, Object> label1 = new LinkedHashMap<>();
        label1.put("index", 0);
        label1.put("name", "人");
        labels.add(label1);
        Map<String, Object> label2 = new LinkedHashMap<>();
        label2.put("index", 1);
        label2.put("name", "车");
        labels.add(label2);
        Map<String, Object> label3 = new LinkedHashMap<>();
        label3.put("index", 2);
        label3.put("name", "船");
        labels.add(label3);
        selectedModel.put("labels", labels);
        aiInfo.put("selected_ai_model", selectedModel);

        return aiInfo;
    }

    // ==================== 辅助方法 ====================

    /** 构造成功回复 */
    private Map<String, Object> success() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", 0);
        return result;
    }

    // ==================== Getter（供测试验证） ====================

    public int getIdentifyOn() { return identifyOn; }
    public int getSpotlightZoomOn() { return spotlightZoomOn; }
    public int getSpotlightZoomState() { return spotlightZoomState; }
    public int getSelectedModelIndex() { return selectedModelIndex; }
    public int getScoreMode() { return scoreMode; }
    public int getScore() { return score; }
    public List<Integer> getFilters() { return filters; }
}
