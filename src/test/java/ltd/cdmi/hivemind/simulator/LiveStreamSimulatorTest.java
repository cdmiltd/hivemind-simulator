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

package ltd.cdmi.hivemind.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.FfmpegWhipPusher;
import ltd.cdmi.hivemind.simulator.handler.LiveStreamSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LiveStreamSimulator 单元测试。
 * <p>覆盖 TDD-SPEC.md 2.18 直播功能 TC-LIVE-001~010：
 * <ul>
 *   <li>live_start_push 解析与幂等更新</li>
 *   <li>live_stop_push 清除推流</li>
 *   <li>live_set_quality 更新清晰度</li>
 *   <li>live_camera_change 三 Dock 差异（Dock1 不支持 / Dock2+Dock3 支持并更新 camera_position）</li>
 *   <li>live_lens_change 三 Dock 均支持（全局 video_type 更新）</li>
 *   <li>直播指令无 Events 进度事件</li>
 * </ul>
 */
class LiveStreamSimulatorTest {

    private static final String VIDEO_ID = "SN/39-0-7/normal-0";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", ""),
                new SimulatorProperties.Media("")
        );
    }

    private MqttProperties testMqttProps() {
        return new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-");
    }

    private LiveStreamSimulator createSimulator(DeviceType dockType) {
        RuntimeConfig runtimeConfig = new RuntimeConfig(testMqttProps(), testProps(), new LiveConfigStore());
        runtimeConfig.setDockType(dockType);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        DiagnosticLogRecorder diagnosticRecorder = new DiagnosticLogRecorder();
        FfmpegWhipPusher ffmpegPusher = new FfmpegWhipPusher(runtimeConfig, diagnosticRecorder);
        return new LiveStreamSimulator(commandHandler, runtimeConfig, ffmpegPusher, diagnosticRecorder);
    }

    private JsonNode json(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    // ==================== TC-LIVE-001：live_start_push 解析与状态记录 ====================

    /**
     * TC-LIVE-001：live_start_push → result=0 + activeStreams 新增记录（含 video_id/url/url_type/quality）
     */
    @Test
    void liveStartPush_returnsResult0_recordsStream() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK3);

        Map<String, Object> result = simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://192.168.1.1:8080/live\",\"url_type\":1,"
                + "\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        assertEquals(0, result.get("result"));

        List<Map<String, Object>> streams = simulator.getActiveStreams();
        assertEquals(1, streams.size());
        Map<String, Object> stream = streams.get(0);
        assertEquals(VIDEO_ID, stream.get("video_id"));
        assertEquals("rtmp://192.168.1.1:8080/live", stream.get("url"));
        assertEquals(1, stream.get("url_type"));
        assertEquals(3, stream.get("quality"));
        // 新推流默认 camera_position=0（舱内）
        assertEquals(0, stream.get("camera_position"));
    }

    // ==================== TC-LIVE-002：live_start_push 幂等更新 ====================

    /**
     * TC-LIVE-002：同 video_id 重复 live_start_push → 不产生重复记录，更新 url/quality
     */
    @Test
    void liveStartPush_duplicateVideoId_idempotentUpdate() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK3);

        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://b/live\",\"url_type\":2,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":4}"));

        List<Map<String, Object>> streams = simulator.getActiveStreams();
        assertEquals(1, streams.size(), "重复 video_id 不应产生重复记录");
        assertEquals("rtmp://b/live", streams.get(0).get("url"));
        assertEquals(4, streams.get(0).get("quality"));
    }

    // ==================== TC-LIVE-003：live_stop_push 清除推流 ====================

    /**
     * TC-LIVE-003：live_stop_push → result=0 + activeStreams 移除该 video_id 记录
     */
    @Test
    void liveStopPush_returnsResult0_removesStream() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK3);
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        Map<String, Object> result = simulator.handle("live_stop_push", json(
                "{\"video_id\":\"" + VIDEO_ID + "\"}"));

        assertEquals(0, result.get("result"));
        assertTrue(simulator.getActiveStreams().isEmpty(), "stop_push 后应清除推流记录");
    }

    // ==================== TC-LIVE-004：live_set_quality 更新清晰度 ====================

    /**
     * TC-LIVE-004：live_set_quality → result=0 + 推流 quality 更新为 4，camera_position 不变
     */
    @Test
    void liveSetQuality_returnsResult0_updatesQuality() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK3);
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        Map<String, Object> result = simulator.handle("live_set_quality", json(
                "{\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":4}"));

        assertEquals(0, result.get("result"));
        assertEquals(4, simulator.getActiveStreams().get(0).get("quality"));
    }

    // ==================== TC-LIVE-005/008：Dock3 live_camera_change 解析与状态跟踪 ====================

    /**
     * TC-LIVE-005/008：Dock3 live_camera_change → result=0 + 推流 camera_position 更新为 1（舱外）
     */
    @Test
    void dock3_liveCameraChange_returnsResult0_updatesCameraPosition() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK3);
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));
        // 初始 camera_position=0
        assertEquals(0, simulator.getActiveStreams().get(0).get("camera_position"));

        Map<String, Object> result = simulator.handle("live_camera_change", json(
                "{\"video_id\":\"" + VIDEO_ID + "\",\"camera_position\":1}"));

        assertEquals(0, result.get("result"));
        assertEquals(1, simulator.getActiveStreams().get(0).get("camera_position"),
                "camera_position 应更新为 1（舱外）");
    }

    // ==================== TC-LIVE-008：Dock2 live_camera_change 支持 ====================

    /**
     * TC-LIVE-008：Dock2 live_camera_change → result=0 + 推流 camera_position 更新（Dock2 同样支持）
     */
    @Test
    void dock2_liveCameraChange_returnsResult0_updatesCameraPosition() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK2);
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        Map<String, Object> result = simulator.handle("live_camera_change", json(
                "{\"video_id\":\"" + VIDEO_ID + "\",\"camera_position\":1}"));

        assertEquals(0, result.get("result"));
        assertEquals(1, simulator.getActiveStreams().get(0).get("camera_position"));
    }

    // ==================== TC-LIVE-007：Dock1 不支持 live_camera_change ====================

    /**
     * TC-LIVE-007：Dock1 live_camera_change → 占位 result=0，不更新 camera_position
     */
    @Test
    void dock1_liveCameraChange_unsupported_returnsPlaceholder_noStateChange() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK1);
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        Map<String, Object> result = simulator.handle("live_camera_change", json(
                "{\"video_id\":\"" + VIDEO_ID + "\",\"camera_position\":1}"));

        assertEquals(0, result.get("result"), "Dock1 收到 live_camera_change 应占位 result=0");
        assertEquals(0, simulator.getActiveStreams().get(0).get("camera_position"),
                "Dock1 不支持 live_camera_change，camera_position 不应被更新");
    }

    // ==================== TC-LIVE-006：live_lens_change 解析与状态跟踪 ====================

    /**
     * TC-LIVE-006：live_lens_change → result=0 + 全局 videoType 更新为 "zoom"
     */
    @Test
    void liveLensChange_returnsResult0_updatesVideoType() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK3);
        assertEquals("normal", simulator.getVideoType(), "初始 videoType 应为 normal");

        Map<String, Object> result = simulator.handle("live_lens_change", json(
                "{\"video_type\":\"zoom\"}"));

        assertEquals(0, result.get("result"));
        assertEquals("zoom", simulator.getVideoType(), "videoType 应更新为 zoom");
    }

    // ==================== TC-LIVE-009：三 Dock 均支持 live_lens_change ====================

    /**
     * TC-LIVE-009：Dock1/Dock2/Dock3 收到 live_lens_change 均更新 videoType（无 Dock 差异）
     */
    @Test
    void allDocks_liveLensChange_supported_updatesVideoType() throws Exception {
        for (DeviceType dockType : new DeviceType[]{DeviceType.DOCK1, DeviceType.DOCK2, DeviceType.DOCK3}) {
            LiveStreamSimulator simulator = createSimulator(dockType);

            Map<String, Object> result = simulator.handle("live_lens_change", json(
                    "{\"video_type\":\"wide\"}"));

            assertEquals(0, result.get("result"), dockType + " 应支持 live_lens_change");
            assertEquals("wide", simulator.getVideoType(), dockType + " 的 videoType 应更新为 wide");
        }
    }

    // ==================== TC-LIVE-010：直播无 Events 进度事件 ====================

    /**
     * TC-LIVE-010：所有直播指令仅回 services_reply result=0，无 events 进度事件
     * <p>设计保证：LiveStreamSimulator 不持有 MqttClientManager 引用，无法发布任何 mqtt 消息，
     * 故无法发送 events 进度事件。此处验证各指令均同步返回 result=0，无异步副作用。</p>
     */
    @Test
    void allLiveCommands_returnResult0_noEventsProgress() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK3);
        // 先启动一路推流，供后续指令操作
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        // 所有直播指令均同步返回 result=0
        assertEquals(0, simulator.handle("live_set_quality",
                json("{\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":4}")).get("result"));
        assertEquals(0, simulator.handle("live_camera_change",
                json("{\"video_id\":\"" + VIDEO_ID + "\",\"camera_position\":1}")).get("result"));
        assertEquals(0, simulator.handle("live_lens_change",
                json("{\"video_type\":\"zoom\"}")).get("result"));
        assertEquals(0, simulator.handle("live_stop_push",
                json("{\"video_id\":\"" + VIDEO_ID + "\"}")).get("result"));
        // LiveStreamSimulator 无 mqtt 依赖，无 events 进度事件（设计保证）
        assertTrue(simulator.getActiveStreams().isEmpty());
    }

    // ==================== 边界：live_camera_change video_id 不存在 ====================

    /**
     * 边界：Dock3 收到 live_camera_change 但 video_id 不在 activeStreams → 静默 result=0，不报错
     */
    @Test
    void dock3_liveCameraChange_unknownVideoId_returnsResult0_silentIgnore() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DeviceType.DOCK3);

        Map<String, Object> result = simulator.handle("live_camera_change", json(
                "{\"video_id\":\"unknown-id\",\"camera_position\":1}"));

        assertEquals(0, result.get("result"), "video_id 不存在时应静默返回 result=0");
        assertTrue(simulator.getActiveStreams().isEmpty());
    }
}
