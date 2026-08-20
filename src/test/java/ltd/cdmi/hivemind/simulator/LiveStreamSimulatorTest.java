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
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.DrcCommandHandler;
import ltd.cdmi.hivemind.simulator.media.FfmpegWhipPusher;
import ltd.cdmi.hivemind.simulator.handler.LiveStreamSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import org.junit.jupiter.api.DisplayName;
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
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null,
                null
        );
    }

    private MqttProperties testMqttProps() {
        return new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-");
    }

    private LiveStreamSimulator createSimulator(DockModel dockType) {
        RuntimeConfig runtimeConfig = new RuntimeConfig(testMqttProps(), testProps(), new LiveConfigStore());
        runtimeConfig.setDockType(dockType);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        DiagnosticLogRecorder diagnosticRecorder = new DiagnosticLogRecorder();
        FfmpegWhipPusher ffmpegPusher = new FfmpegWhipPusher(runtimeConfig, diagnosticRecorder);
        return new LiveStreamSimulator(commandHandler, Mockito.mock(DrcCommandHandler.class), runtimeConfig, ffmpegPusher, diagnosticRecorder);
    }

    private LiveStreamSimulator createPilotSimulator(RcModel controllerType) {
        RuntimeConfig runtimeConfig = new RuntimeConfig(testMqttProps(), testProps(), new LiveConfigStore());
        runtimeConfig.setDeviceMode(ltd.cdmi.hivemind.simulator.device.DeviceMode.PILOT);
        runtimeConfig.setControllerType(controllerType);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);
        DiagnosticLogRecorder diagnosticRecorder = new DiagnosticLogRecorder();
        FfmpegWhipPusher ffmpegPusher = new FfmpegWhipPusher(runtimeConfig, diagnosticRecorder);
        return new LiveStreamSimulator(commandHandler, Mockito.mock(DrcCommandHandler.class), runtimeConfig, ffmpegPusher, diagnosticRecorder);
    }

    private JsonNode json(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    // ==================== TC-LIVE-001：live_start_push 解析与状态记录 ====================

    /**
     * TC-LIVE-001：live_start_push → result=0 + activeStreams 新增记录（含 video_id/url/url_type/quality）
     */
    @DisplayName("TC-LIVE-001：live_start_push 解析与状态记录")
    @Test
    void liveStartPush_returnsResult0_recordsStream() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK3);

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
    @DisplayName("TC-LIVE-002：live_start_push 幂等更新")
    @Test
    void liveStartPush_duplicateVideoId_idempotentUpdate() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK3);

        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://b/live\",\"url_type\":3,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":4}"));

        List<Map<String, Object>> streams = simulator.getActiveStreams();
        assertEquals(1, streams.size(), "重复 video_id 不应产生重复记录");
        assertEquals("rtmp://b/live", streams.get(0).get("url"));
        assertEquals(4, streams.get(0).get("quality"));
    }

    // ==================== TC-LIVE-003：live_stop_push 清除推流 ====================

    /**
     * TC-LIVE-003：live_stop_push → result=0 + activeStreams 移除该 video_id 记录
     */
    @DisplayName("TC-LIVE-003：live_stop_push 清除推流")
    @Test
    void liveStopPush_returnsResult0_removesStream() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK3);
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
    @DisplayName("TC-LIVE-004：live_set_quality 更新清晰度")
    @Test
    void liveSetQuality_returnsResult0_updatesQuality() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK3);
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
    @DisplayName("TC-LIVE-005：live_camera_change 解析与状态跟踪")
    @Test
    void dock3_liveCameraChange_returnsResult0_updatesCameraPosition() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK3);
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
    @DisplayName("TC-LIVE-008：Dock2/Dock3 支持 live_camera_change")
    @Test
    void dock2_liveCameraChange_returnsResult0_updatesCameraPosition() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK2);
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
    @DisplayName("TC-LIVE-007：Dock1 不支持 live_camera_change")
    @Test
    void dock1_liveCameraChange_unsupported_returnsPlaceholder_noStateChange() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK1);
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
    @DisplayName("TC-LIVE-006：live_lens_change 解析与状态跟踪")
    @Test
    void liveLensChange_returnsResult0_updatesVideoType() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK3);
        assertEquals("normal", simulator.getVideoType(), "初始 videoType 应为 normal");

        Map<String, Object> result = simulator.handle("live_lens_change", json(
                "{\"video_type\":\"zoom\"}"));

        assertEquals(0, result.get("result"));
        assertEquals("zoom", simulator.getVideoType(), "videoType 应更新为 zoom");
    }

    // ==================== TC-LIVE-009：三 Dock 均支持 live_lens_change ====================

    /**
     * TC-LIVE-009：Dock1/Dock2/Dock3 收到 live_lens_change 均更新 videoType（无 video_id，全局更新）
     */
    @DisplayName("TC-LIVE-009：三 Dock 均支持 live_lens_change")
    @Test
    void allDocks_liveLensChange_supported_updatesVideoType() throws Exception {
        for (DockModel dockType : new DockModel[]{DockModel.DOCK1, DockModel.DOCK2, DockModel.DOCK3}) {
            LiveStreamSimulator simulator = createSimulator(dockType);

            Map<String, Object> result = simulator.handle("live_lens_change", json(
                    "{\"video_type\":\"wide\"}"));

            assertEquals(0, result.get("result"), dockType + " 应支持 live_lens_change");
            assertEquals("wide", simulator.getVideoType(), dockType + " 的 videoType 应更新为 wide");
        }
    }

    // ==================== TC-ONLINE-015：Pilot 模式 live_lens_change 按 video_id 精准切换 ====================

    /**
     * TC-ONLINE-015：其他 Pilot 机型（RC_PLUS）的 live_lens_change 走 Service Topic，
     * 解析 video_id + video_type，按 video_id 精准切换对应推流的 video_type
     */
    @DisplayName("TC-ONLINE-015：Pilot 模式 live_lens_change 按 video_id 精准切换")
    @Test
    void pilotLiveLensChange_updatesVideoTypeByVideoId() throws Exception {
        LiveStreamSimulator simulator = createPilotSimulator(RcModel.RC_PLUS);
        // 先启动一路推流
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));
        // 初始 video_type 应为 normal
        List<Map<String, Object>> streams = simulator.getActiveStreams();
        assertEquals(1, streams.size(), "应有一路推流");
        assertEquals("normal", streams.get(0).get("video_type"), "初始 video_type 应为 normal");

        // 按 video_id 精准切换为 ir（RC Plus 枚举，非 thermal）
        Map<String, Object> result = simulator.handle("live_lens_change", json(
                "{\"video_id\":\"" + VIDEO_ID + "\",\"video_type\":\"ir\"}"));

        assertEquals(0, result.get("result"));
        streams = simulator.getActiveStreams();
        assertEquals("ir", streams.get(0).get("video_type"), "video_type 应按 video_id 精准切换为 ir");
    }

    /**
     * TC-ONLINE-015：RC Plus 2 的 live_lens_change 走 DRC Topic，
     * Service Topic 收到时容错返回 result=0（不更新状态）
     */
    @DisplayName("TC-ONLINE-015：RC Plus 2 Service Topic live_lens_change 容错返回")
    @Test
    void rcPlus2LiveLensChange_serviceTopic_returnsResult0_noUpdate() throws Exception {
        LiveStreamSimulator simulator = createPilotSimulator(RcModel.RC_PLUS_2);
        simulator.handle("live_start_push", json(
                "{\"url\":\"rtmp://a/live\",\"url_type\":1,\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        Map<String, Object> result = simulator.handle("live_lens_change", json(
                "{\"video_id\":\"" + VIDEO_ID + "\",\"video_type\":\"thermal\"}"));  // thermal 为 RC Plus 2 枚举（RC Plus 2 走 DRC Topic，此处验证 Service Topic 容错）

        assertEquals(0, result.get("result"), "RC Plus 2 Service Topic live_lens_change 应容错返回 result=0");
        // 容错返回，不更新推流的 video_type
        List<Map<String, Object>> streams = simulator.getActiveStreams();
        assertEquals("normal", streams.get(0).get("video_type"), "容错返回不应更新 video_type");
    }

    // ==================== TC-LIVE-010：直播无 Events 进度事件 ====================

    /**
     * TC-LIVE-010：所有直播指令仅回 services_reply result=0，无 events 进度事件
     * <p>设计保证：LiveStreamSimulator 不持有 MqttClientManager 引用，无法发布任何 mqtt 消息，
     * 故无法发送 events 进度事件。此处验证各指令均同步返回 result=0，无异步副作用。</p>
     */
    @DisplayName("TC-LIVE-010：直播无 Events 进度事件")
    @Test
    void allLiveCommands_returnResult0_noEventsProgress() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK3);
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
    @DisplayName("补充测试：live_camera_change video_id 不存在时静默返回")
    @Test
    void dock3_liveCameraChange_unknownVideoId_returnsResult0_silentIgnore() throws Exception {
        LiveStreamSimulator simulator = createSimulator(DockModel.DOCK3);

        Map<String, Object> result = simulator.handle("live_camera_change", json(
                "{\"video_id\":\"unknown-id\",\"camera_position\":1}"));

        assertEquals(0, result.get("result"), "video_id 不存在时应静默返回 result=0");
        assertTrue(simulator.getActiveStreams().isEmpty());
    }

    // ==================== TC-LIVE-016：WHIP 降级 RTMP 推流 ====================

    /**
     * TC-LIVE-016：url_type=4 (WebRTC) 但 ffmpeg 不支持 WHIP 时，自动降级为 RTMP 推流。
     * <p>验证 WebRTC URL 被正确转换为 RTMP URL，并以 RTMP 方式推流。</p>
     */
    @DisplayName("TC-LIVE-016：WHIP 降级 RTMP 推流")
    @Test
    void whipNotSupported_fallsBackToRtmp() throws Exception {
        RuntimeConfig runtimeConfig = new RuntimeConfig(testMqttProps(), testProps(), new LiveConfigStore());
        runtimeConfig.setDockType(DockModel.DOCK3);

        // mock ffmpegPusher：不支持 WHIP，但支持 RTMP
        FfmpegWhipPusher mockPusher = Mockito.mock(FfmpegWhipPusher.class);
        Mockito.when(mockPusher.isRealPushEnabled()).thenReturn(true);
        Mockito.when(mockPusher.isPushAvailable(FfmpegWhipPusher.URL_TYPE_WEBRTC)).thenReturn(false);
        Mockito.when(mockPusher.isPushAvailable(FfmpegWhipPusher.URL_TYPE_RTMP)).thenReturn(true);
        Mockito.when(mockPusher.startPush(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.eq(FfmpegWhipPusher.URL_TYPE_RTMP))).thenReturn(true);

        DiagnosticLogRecorder diagnosticRecorder = new DiagnosticLogRecorder();
        LiveStreamSimulator simulator = new LiveStreamSimulator(
                Mockito.mock(ServiceCommandHandler.class), Mockito.mock(DrcCommandHandler.class), runtimeConfig, mockPusher, diagnosticRecorder);

        String whipUrl = "http://172.30.48.1:3001/stream/index/api/webrtc?app=live&stream=test_stream&type=push";
        Map<String, Object> result = simulator.handle("live_start_push", json(
                "{\"url\":\"" + whipUrl + "\",\"url_type\":4,"
                + "\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        assertEquals(0, result.get("result"));

        // 验证 startPush 以 RTMP URL 调用（WebRTC URL 已被转换）
        Mockito.verify(mockPusher).startPush(
                Mockito.eq(VIDEO_ID),
                Mockito.eq("rtmp://172.30.48.1:1935/live/test_stream"),
                Mockito.anyString(),
                Mockito.eq(FfmpegWhipPusher.URL_TYPE_RTMP));
    }

    /**
     * TC-LIVE-017：ffmpeg 既不支持 WHIP 也不支持 RTMP 时，降级为协议模拟（不推流）。
     */
    @DisplayName("TC-LIVE-017：WHIP 与 RTMP 均不支持时降级协议模拟")
    @Test
    void whipAndRtmpBothUnsupported_fallsBackToProtocolSimulation() throws Exception {
        RuntimeConfig runtimeConfig = new RuntimeConfig(testMqttProps(), testProps(), new LiveConfigStore());
        runtimeConfig.setDockType(DockModel.DOCK3);

        FfmpegWhipPusher mockPusher = Mockito.mock(FfmpegWhipPusher.class);
        Mockito.when(mockPusher.isRealPushEnabled()).thenReturn(true);
        Mockito.when(mockPusher.isPushAvailable(FfmpegWhipPusher.URL_TYPE_WEBRTC)).thenReturn(false);
        Mockito.when(mockPusher.isPushAvailable(FfmpegWhipPusher.URL_TYPE_RTMP)).thenReturn(false);

        DiagnosticLogRecorder diagnosticRecorder = new DiagnosticLogRecorder();
        LiveStreamSimulator simulator = new LiveStreamSimulator(
                Mockito.mock(ServiceCommandHandler.class), Mockito.mock(DrcCommandHandler.class), runtimeConfig, mockPusher, diagnosticRecorder);

        String whipUrl = "http://172.30.48.1:3001/stream/index/api/webrtc?app=live&stream=test_stream&type=push";
        Map<String, Object> result = simulator.handle("live_start_push", json(
                "{\"url\":\"" + whipUrl + "\",\"url_type\":4,"
                + "\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        assertEquals(0, result.get("result"));
        // startPush 不应被调用
        Mockito.verify(mockPusher, Mockito.never()).startPush(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyInt());
    }

    /**
     * TC-LIVE-018：WebRTC URL 格式异常时（无 stream 参数），降级为协议模拟（不推流）。
     */
    @DisplayName("TC-LIVE-018：WebRTC URL 异常时降级协议模拟")
    @Test
    void whipUrlMalformed_fallsBackToProtocolSimulation() throws Exception {
        RuntimeConfig runtimeConfig = new RuntimeConfig(testMqttProps(), testProps(), new LiveConfigStore());
        runtimeConfig.setDockType(DockModel.DOCK3);

        FfmpegWhipPusher mockPusher = Mockito.mock(FfmpegWhipPusher.class);
        Mockito.when(mockPusher.isRealPushEnabled()).thenReturn(true);
        Mockito.when(mockPusher.isPushAvailable(FfmpegWhipPusher.URL_TYPE_WEBRTC)).thenReturn(false);
        Mockito.when(mockPusher.isPushAvailable(FfmpegWhipPusher.URL_TYPE_RTMP)).thenReturn(true);

        DiagnosticLogRecorder diagnosticRecorder = new DiagnosticLogRecorder();
        LiveStreamSimulator simulator = new LiveStreamSimulator(
                Mockito.mock(ServiceCommandHandler.class), Mockito.mock(DrcCommandHandler.class), runtimeConfig, mockPusher, diagnosticRecorder);

        // 缺少 stream 参数的 WebRTC URL
        String malformedUrl = "http://172.30.48.1:3001/stream/index/api/webrtc?app=live&type=push";
        Map<String, Object> result = simulator.handle("live_start_push", json(
                "{\"url\":\"" + malformedUrl + "\",\"url_type\":4,"
                + "\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":3}"));

        assertEquals(0, result.get("result"));
        // startPush 不应被调用
        Mockito.verify(mockPusher, Mockito.never()).startPush(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyInt());
    }

    /**
     * TC-LIVE-019：url_type=3 (GB28181) 但 URL 以 rtmp:// 开头时，自动按 RTMP 推流（容错）。
     */
    @DisplayName("TC-LIVE-019：url_type 与 URL 不匹配时容错按 RTMP 推流")
    @Test
    void urlTypeMismatch_rtmpUrl_autoRtmpPush() throws Exception {
        RuntimeConfig runtimeConfig = new RuntimeConfig(testMqttProps(), testProps(), new LiveConfigStore());
        runtimeConfig.setDockType(DockModel.DOCK3);

        FfmpegWhipPusher mockPusher = Mockito.mock(FfmpegWhipPusher.class);
        Mockito.when(mockPusher.isRealPushEnabled()).thenReturn(true);
        Mockito.when(mockPusher.isPushAvailable(FfmpegWhipPusher.URL_TYPE_RTMP)).thenReturn(true);
        Mockito.when(mockPusher.startPush(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.eq(FfmpegWhipPusher.URL_TYPE_RTMP))).thenReturn(true);

        DiagnosticLogRecorder diagnosticRecorder = new DiagnosticLogRecorder();
        LiveStreamSimulator simulator = new LiveStreamSimulator(
                Mockito.mock(ServiceCommandHandler.class), Mockito.mock(DrcCommandHandler.class), runtimeConfig, mockPusher, diagnosticRecorder);

        // url_type=3 (GB28181) + url=rtmp://...（平台配置不匹配）
        String rtmpUrl = "rtmp://192.168.169.154:1935/live/7UUXN1Q00A008W_dock_outdoor";
        Map<String, Object> result = simulator.handle("live_start_push", json(
                "{\"url\":\"" + rtmpUrl + "\",\"url_type\":3,"
                + "\"video_id\":\"" + VIDEO_ID + "\",\"video_quality\":0}"));

        assertEquals(0, result.get("result"));
        // 验证 startPush 以 RTMP 方式调用
        Mockito.verify(mockPusher).startPush(
                Mockito.eq(VIDEO_ID),
                Mockito.eq(rtmpUrl),
                Mockito.anyString(),
                Mockito.eq(FfmpegWhipPusher.URL_TYPE_RTMP));
    }
}
