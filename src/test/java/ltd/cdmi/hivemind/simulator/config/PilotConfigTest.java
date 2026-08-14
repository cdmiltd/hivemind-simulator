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

package ltd.cdmi.hivemind.simulator.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pilot 上云配置参数化测试（token + map + media + live + mop 模块）。
 * <p>覆盖 RuntimeConfig 对 hivemindHttpToken / hivemindWsToken / mapUserName / mapElementPreName
 * 以及 media 自动上传 / live 直播方式 / mop 数据传输配置的初始化（从 SimulatorProperties 读取）
 * 和运行时 getter/setter 行为。
 */
class PilotConfigTest {

    /** 构造一个完整配置（含 Hivemind token、Mop、Map 及非默认 media/live 参数），用于初始化测试 */
    private SimulatorProperties fullProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", "video-by-manual"),
                new SimulatorProperties.Media("/media", true, 1, true, 1),
                new SimulatorProperties.Hivemind(
                        new SimulatorProperties.Hivemind.Http("http://hivemind:8080", 5000, "http-token-abc"),
                        new SimulatorProperties.Hivemind.WebSocket("ws://hivemind:8081", "ws-token-xyz")
                ),
                new SimulatorProperties.Mop("ws://mop-host", "mop-token-xyz"),
                new SimulatorProperties.Map("pilot-user", "sim-")
        );
    }

    /** 构造一个 null Hivemind + null Mop + null Map 的配置，用于验证降级为默认值/空串 */
    private SimulatorProperties emptyProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null
        );
    }

    private MqttProperties testMqttProps() {
        return new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-");
    }

    // ==================== 初始化：从 SimulatorProperties 读取 ====================

    @Test
    void runtimeConfigInitializesHivemindHttpTokenFromProps() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        assertEquals("http-token-abc", rc.getHivemindHttpToken());
    }

    @Test
    void runtimeConfigInitializesHivemindWsTokenFromProps() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        assertEquals("ws-token-xyz", rc.getHivemindWsToken());
    }

    @Test
    void runtimeConfigInitializesMapUserNameFromProps() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        assertEquals("pilot-user", rc.getMapUserName());
    }

    @Test
    void runtimeConfigInitializesMapElementPreNameFromProps() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        assertEquals("sim-", rc.getMapElementPreName());
    }

    // ==================== 初始化：null 安全（缺失配置降级为空串） ====================

    @Test
    void runtimeConfigInitializesTokensToEmptyStringWhenHivemindNull() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        assertEquals("", rc.getHivemindHttpToken());
        assertEquals("", rc.getHivemindWsToken());
    }

    @Test
    void runtimeConfigInitializesMapFieldsToEmptyStringWhenMapNull() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        assertEquals("", rc.getMapUserName());
        assertEquals("", rc.getMapElementPreName());
    }

    // ==================== 运行时 getter/setter ====================

    @Test
    void hivemindHttpTokenSetterUpdatesValue() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        rc.setHivemindHttpToken("new-http-token");
        assertEquals("new-http-token", rc.getHivemindHttpToken());
    }

    @Test
    void hivemindWsTokenSetterUpdatesValue() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        rc.setHivemindWsToken("new-ws-token");
        assertEquals("new-ws-token", rc.getHivemindWsToken());
    }

    @Test
    void mapUserNameSetterUpdatesValue() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        rc.setMapUserName("updated-user");
        assertEquals("updated-user", rc.getMapUserName());
    }

    @Test
    void mapElementPreNameSetterUpdatesValue() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        rc.setMapElementPreName("updated-prefix-");
        assertEquals("updated-prefix-", rc.getMapElementPreName());
    }

    @Test
    void tokenSettersAcceptNullValue() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        // setter 接受 null（实际使用方应自行做 null 安全处理）
        rc.setHivemindHttpToken(null);
        assertNull(rc.getHivemindHttpToken());
        rc.setHivemindWsToken(null);
        assertNull(rc.getHivemindWsToken());
    }

    @Test
    void mapSettersAcceptNullValue() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        rc.setMapUserName(null);
        assertNull(rc.getMapUserName());
        rc.setMapElementPreName(null);
        assertNull(rc.getMapElementPreName());
    }

    // ==================== media 自动上传配置：初始化 ====================

    @Test
    void runtimeConfigInitializesMediaAutoUploadFieldsFromProps() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        assertTrue(rc.isMediaAutoUploadPhoto(), "auto-upload-photo 应从 props 读取为 true");
        assertEquals(1, rc.getMediaAutoUploadPhotoType(), "auto-upload-photo-type 应从 props 读取为 1");
        assertTrue(rc.isMediaAutoUploadVideo(), "auto-upload-video 应从 props 读取为 true");
        assertEquals(1, rc.getMediaDownloadOwner(), "download-owner 应从 props 读取为 1");
    }

    @Test
    void runtimeConfigInitializesMediaAutoUploadFieldsToDefaultsWhenMediaNull() {
        // emptyProps 的 Media 提供显式默认值（false/0/false/0）
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        assertFalse(rc.isMediaAutoUploadPhoto());
        assertEquals(0, rc.getMediaAutoUploadPhotoType());
        assertFalse(rc.isMediaAutoUploadVideo());
        assertEquals(0, rc.getMediaDownloadOwner());
    }

    // ==================== media 自动上传配置：运行时 getter/setter ====================

    @Test
    void mediaAutoUploadSettersUpdateValues() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        rc.setMediaAutoUploadPhoto(true);
        rc.setMediaAutoUploadPhotoType(1);
        rc.setMediaAutoUploadVideo(true);
        rc.setMediaDownloadOwner(1);
        assertTrue(rc.isMediaAutoUploadPhoto());
        assertEquals(1, rc.getMediaAutoUploadPhotoType());
        assertTrue(rc.isMediaAutoUploadVideo());
        assertEquals(1, rc.getMediaDownloadOwner());
    }

    // ==================== live 直播方式：初始化 ====================

    @Test
    void runtimeConfigInitializesLiveVideoPublishTypeFromProps() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        assertEquals("video-by-manual", rc.getLiveVideoPublishType());
    }

    @Test
    void runtimeConfigInitializesLiveVideoPublishTypeToDefaultWhenNull() {
        // emptyProps 的 Live.videoPublishType 为 null，应降级为默认 "video-on-demand"
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        assertEquals("video-on-demand", rc.getLiveVideoPublishType());
    }

    @Test
    void liveVideoPublishTypeSetterUpdatesValue() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        rc.setLiveVideoPublishType("video-demand-aux-manual");
        assertEquals("video-demand-aux-manual", rc.getLiveVideoPublishType());
    }

    // ==================== mop 数据传输配置：初始化 ====================

    @Test
    void runtimeConfigInitializesMopHostAndTokenFromProps() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        assertEquals("ws://mop-host", rc.getMopHost());
        assertEquals("mop-token-xyz", rc.getMopToken());
    }

    @Test
    void runtimeConfigInitializesMopFieldsToEmptyStringWhenMopNull() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        assertEquals("", rc.getMopHost());
        assertEquals("", rc.getMopToken());
    }

    // ==================== mop 数据传输配置：运行时 getter/setter ====================

    @Test
    void mopSettersUpdateValues() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), emptyProps(), new LiveConfigStore());
        rc.setMopHost("ws://new-mop");
        rc.setMopToken("new-mop-token");
        assertEquals("ws://new-mop", rc.getMopHost());
        assertEquals("new-mop-token", rc.getMopToken());
    }

    @Test
    void mopSettersAcceptNullValue() {
        RuntimeConfig rc = new RuntimeConfig(testMqttProps(), fullProps(), new LiveConfigStore());
        rc.setMopHost(null);
        assertNull(rc.getMopHost());
        rc.setMopToken(null);
        assertNull(rc.getMopToken());
    }
}
