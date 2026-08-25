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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link AiSimulator} 单元测试。
 * <p>对应 TDD-SPEC TC-DRC-AI-001 ~ TC-DRC-AI-007，覆盖 10 个 AI DRC 指令处理与状态动态推送。
 * <p>核实依据：DJI Cloud API <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html">Dock3 远程控制</a>。
 */
class AiSimulatorTest {

    private MqttClientManager mqtt;
    private RuntimeConfig runtimeConfig;
    private AiSimulator aiSimulator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mqtt = mock(MqttClientManager.class);
        runtimeConfig = mock(RuntimeConfig.class);
        when(runtimeConfig.getGatewaySn()).thenReturn("test-gateway-sn");
        aiSimulator = new AiSimulator(mqtt, runtimeConfig, new DockTopicSchema());
    }

    // ==================== TC-DRC-AI-001：drc_ai_identify_set（AI 识别开关） ====================

    @DisplayName("TC-DRC-AI-001：drc_ai_identify_set 设置 identify_on=1 后状态更新并推送 drc_ai_info_push")
    @Test
    void handleAiIdentifySetUpdatesStateAndPushesAiInfo() {
        ObjectNode data = objectMapper.createObjectNode().put("identify_on", 1);

        Map<String, Object> result = aiSimulator.handleAiIdentifySet(data);

        // 回复 result=0
        assertEquals(0, result.get("result"));
        // 状态已更新
        assertEquals(1, aiSimulator.getIdentifyOn());
        // 触发一次 drc_ai_info_push 推送
        verify(mqtt, times(1)).publishJson(anyString(), any());
    }

    // ==================== TC-DRC-AI-002：drc_ai_model_select（AI 模型选择） ====================

    @DisplayName("TC-DRC-AI-002：drc_ai_model_select 更新 selected_model_index 并推送状态")
    @Test
    void handleAiModelSelectUpdatesSelectedIndex() {
        ObjectNode data = objectMapper.createObjectNode().put("index", 2);

        Map<String, Object> result = aiSimulator.handleAiModelSelect(data);

        assertEquals(0, result.get("result"));
        assertEquals(2, aiSimulator.getSelectedModelIndex());
        verify(mqtt, times(1)).publishJson(anyString(), any());
    }

    // ==================== TC-DRC-AI-003：drc_ai_identify_score_mode_set（置信度模式） ====================

    @DisplayName("TC-DRC-AI-003：drc_ai_identify_score_mode_set 设置 score_mode=3 后状态更新")
    @Test
    void handleAiIdentifyScoreModeSetUpdatesScoreMode() {
        ObjectNode data = objectMapper.createObjectNode().put("score_mode", 3);

        Map<String, Object> result = aiSimulator.handleAiIdentifyScoreModeSet(data);

        assertEquals(0, result.get("result"));
        assertEquals(3, aiSimulator.getScoreMode());
        verify(mqtt, times(1)).publishJson(anyString(), any());
    }

    // ==================== TC-DRC-AI-004：drc_ai_spotlight_zoom_set（AI 跟随开关） ====================

    @DisplayName("TC-DRC-AI-004：drc_ai_spotlight_zoom_set 设置 spotlight_zoom_on=1 后状态更新")
    @Test
    void handleAiSpotlightZoomSetUpdatesOnState() {
        ObjectNode data = objectMapper.createObjectNode().put("spotlight_zoom_on", 1);

        Map<String, Object> result = aiSimulator.handleAiSpotlightZoomSet(data);

        assertEquals(0, result.get("result"));
        assertEquals(1, aiSimulator.getSpotlightZoomOn());
        verify(mqtt, times(1)).publishJson(anyString(), any());
    }

    // ==================== TC-DRC-AI-005：drc_ai_spotlight_zoom_track（目标跟随） ====================

    @DisplayName("TC-DRC-AI-005：drc_ai_spotlight_zoom_track 触发后 spotlight_zoom_state 变为 1（跟踪中）")
    @Test
    void handleAiSpotlightZoomTrackSetsStateToTracking() {
        ObjectNode data = objectMapper.createObjectNode().put("target_index", 0);

        Map<String, Object> result = aiSimulator.handleAiSpotlightZoomTrack(data);

        assertEquals(0, result.get("result"));
        assertEquals(1, aiSimulator.getSpotlightZoomState());
        verify(mqtt, times(1)).publishJson(anyString(), any());
    }

    // ==================== TC-DRC-AI-006：drc_ai_spotlight_zoom_stop（停止跟随） ====================

    @DisplayName("TC-DRC-AI-006：drc_ai_spotlight_zoom_stop 后 spotlight_zoom_state 变为 0（空闲）")
    @Test
    void handleAiSpotlightZoomStopResetsStateToIdle() {
        // 先进入跟踪状态
        aiSimulator.handleAiSpotlightZoomTrack(objectMapper.createObjectNode().put("target_index", 0));
        assertEquals(1, aiSimulator.getSpotlightZoomState());
        reset(mqtt);

        ObjectNode data = objectMapper.createObjectNode();
        Map<String, Object> result = aiSimulator.handleAiSpotlightZoomStop(data);

        assertEquals(0, result.get("result"));
        assertEquals(0, aiSimulator.getSpotlightZoomState());
        verify(mqtt, times(1)).publishJson(anyString(), any());
    }

    // ==================== TC-DRC-AI-007：drc_ai_info_push 状态动态更新 ====================

    @DisplayName("TC-DRC-AI-007：buildAiInfo 反映指令变更后的动态状态（identify_on=1，非固定值 0）")
    @Test
    void buildAiInfoReflectsDynamicStateAfterCommand() {
        // 默认 identify_on=0，先验证初始状态
        Map<String, Object> initial = aiSimulator.buildAiInfo();
        assertEquals(0, initial.get("identify_on"));

        // 下发指令开启 AI 识别
        aiSimulator.handleAiIdentifySet(objectMapper.createObjectNode().put("identify_on", 1));

        // buildAiInfo 应反映新状态
        Map<String, Object> aiInfo = aiSimulator.buildAiInfo();
        assertEquals(1, aiInfo.get("identify_on"));
        assertNotNull(aiInfo.get("ai_spotlight_zoom"));
        assertNotNull(aiInfo.get("ai_model_list"));
        assertNotNull(aiInfo.get("selected_ai_model"));
    }

    // ==================== 补充测试：剩余 4 个指令覆盖 ====================

    @DisplayName("补充测试：drc_ai_identify_score_set 更新 score")
    @Test
    void handleAiIdentifyScoreSetUpdatesScore() {
        ObjectNode data = objectMapper.createObjectNode().put("score", 80);

        Map<String, Object> result = aiSimulator.handleAiIdentifyScoreSet(data);

        assertEquals(0, result.get("result"));
        assertEquals(80, aiSimulator.getScore());
        verify(mqtt, times(1)).publishJson(anyString(), any());
    }

    @DisplayName("补充测试：drc_ai_identify_score_reset 重置 score 到默认值 100")
    @Test
    void handleAiIdentifyScoreResetResetsScore() {
        // 先设 score=80，再重置
        aiSimulator.handleAiIdentifyScoreSet(objectMapper.createObjectNode().put("score", 80));
        assertEquals(80, aiSimulator.getScore());

        ObjectNode data = objectMapper.createObjectNode();
        Map<String, Object> result = aiSimulator.handleAiIdentifyScoreReset(data);

        assertEquals(0, result.get("result"));
        assertEquals(100, aiSimulator.getScore(), "重置后 score 应恢复默认值 100");
    }

    @DisplayName("补充测试：drc_ai_identify_filter_set 更新 filters")
    @Test
    void handleAiIdentifyFilterSetUpdatesFilters() {
        ObjectNode data = objectMapper.createObjectNode();
        data.putArray("filters").add(2).add(3);

        Map<String, Object> result = aiSimulator.handleAiIdentifyFilterSet(data);

        assertEquals(0, result.get("result"));
        assertEquals(List.of(2, 3), aiSimulator.getFilters());
        verify(mqtt, times(1)).publishJson(anyString(), any());
    }

    @DisplayName("补充测试：drc_ai_spotlight_zoom_select 触发后 state 变为 2（框选等待确认）")
    @Test
    void handleAiSpotlightZoomSelectSetsStateToSelecting() {
        Map<String, Object> result = aiSimulator.handleAiSpotlightZoomSelect(objectMapper.createObjectNode());

        assertEquals(0, result.get("result"));
        assertEquals(2, aiSimulator.getSpotlightZoomState());
    }

    @DisplayName("补充测试：drc_ai_spotlight_zoom_confirm 触发后 state 变为 1（跟踪中）")
    @Test
    void handleAiSpotlightZoomConfirmSetsStateToTracking() {
        // 先进入框选等待确认状态
        aiSimulator.handleAiSpotlightZoomSelect(objectMapper.createObjectNode());
        assertEquals(2, aiSimulator.getSpotlightZoomState());
        reset(mqtt);

        Map<String, Object> result = aiSimulator.handleAiSpotlightZoomConfirm(objectMapper.createObjectNode());

        assertEquals(0, result.get("result"));
        assertEquals(1, aiSimulator.getSpotlightZoomState());
        verify(mqtt, times(1)).publishJson(anyString(), any());
    }

    // ==================== 协议字段完整性验证 ====================

    @DisplayName("补充测试：buildAiInfo 包含 DJI 协议必需字段")
    @Test
    void buildAiInfoContainsRequiredProtocolFields() {
        Map<String, Object> aiInfo = aiSimulator.buildAiInfo();

        // 顶层字段
        assertTrue(aiInfo.containsKey("identify_on"));
        assertTrue(aiInfo.containsKey("spotlight_zoom_on"));
        assertTrue(aiInfo.containsKey("ai_spotlight_zoom"));
        assertTrue(aiInfo.containsKey("ai_model_list"));
        assertTrue(aiInfo.containsKey("selected_ai_model"));

        // ai_spotlight_zoom 子字段
        Map<String, Object> spotlightZoom = (Map<String, Object>) aiInfo.get("ai_spotlight_zoom");
        assertTrue(spotlightZoom.containsKey("state"));
        assertTrue(spotlightZoom.containsKey("state_reason"));

        // selected_ai_model 子字段
        Map<String, Object> selectedModel = (Map<String, Object>) aiInfo.get("selected_ai_model");
        assertTrue(selectedModel.containsKey("index"));
        assertTrue(selectedModel.containsKey("score"));
        assertTrue(selectedModel.containsKey("score_mode"));
        assertTrue(selectedModel.containsKey("image_source"));
        assertTrue(selectedModel.containsKey("digital_effect"));
        assertTrue(selectedModel.containsKey("filters"));
        assertTrue(selectedModel.containsKey("labels"));
    }
}
