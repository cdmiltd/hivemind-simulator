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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FfmpegWhipPusher 单元测试。
 * <p>覆盖 TDD-SPEC.md 2.19 直播功能 TC-LIVE-018（ffmpeg -muxers 输出格式解析兼容 7.x/8.x）：
 * ffmpeg 8.x 的 muxer 行 "E" 后为两个空格（{@code E  flv}），旧解析 {@code startsWith("E flv")}
 * 匹配失败导致 RTMP 能力被误报不支持（Ubuntu 26.04 / Docker 镜像内置 ffmpeg 8.0.1 实测）。
 */
class FfmpegWhipPusherTest {

    /** ffmpeg 8.0.1（Ubuntu 26.04 / Docker 镜像）实际输出片段，muxer 行 E 后两个空格 */
    private static final String FFMPEG8_OUTPUT = """
            ffmpeg version 8.0.1-3ubuntu2 Copyright (c) 2000-2025 the FFmpeg developers
              built with gcc 15 (Ubuntu 15.2.0-13ubuntu3)
              libavutil      60.  8.100 / 60.  8.100
            File formats:
             D. = Demuxing supported
             .E = Muxing supported
             --
              E  3g2             3GP2 (3GPP2 file format)
              E  3gp             3GP (3GPP file format)
              E  flv             FLV (Flash Video)
              E  matroska        Matroska
              E  mp4             MP4 (MPEG-4 Part 14)
              E  rtp             RTP output
            """;

    /** ffmpeg 7.x 及更早版本输出片段，muxer 行 E 后一个空格 */
    private static final String FFMPEG7_OUTPUT = """
            File formats:
             D. = Demuxing supported
             .E = Muxing supported
             --
              E flv             FLV (Flash Video)
              E mp4             MP4 (MPEG-4 Part 14)
            """;

    /** 含 whip muxer 的特殊编译版 ffmpeg（live777/ffmpeg-webrtc）输出片段 */
    private static final String WHIP_OUTPUT = """
            File formats:
             D. = Demuxing supported
             .E = Muxing supported
             --
              E  flv             FLV (Flash Video)
              E  whip            WebRTC HTTP Ingestion Protocol
            """;

    @Test
    @DisplayName("ffmpeg 8.x 格式（E 后两个空格）能识别 flv muxer")
    void parseFfmpeg8Format() {
        boolean[] result = FfmpegWhipPusher.parseMuxers(FFMPEG8_OUTPUT);
        assertFalse(result[0], "ffmpeg 8.0.1 无 whip muxer");
        assertTrue(result[1], "ffmpeg 8.0.1 有 flv muxer（RTMP 可用），旧解析在此误报 false");
    }

    @Test
    @DisplayName("ffmpeg 7.x 格式（E 后一个空格）仍能识别 flv muxer")
    void parseFfmpeg7Format() {
        boolean[] result = FfmpegWhipPusher.parseMuxers(FFMPEG7_OUTPUT);
        assertFalse(result[0]);
        assertTrue(result[1]);
    }

    @Test
    @DisplayName("特殊编译版 ffmpeg 可同时识别 whip 与 flv muxer")
    void parseWhipBuild() {
        boolean[] result = FfmpegWhipPusher.parseMuxers(WHIP_OUTPUT);
        assertTrue(result[0], "whip muxer 应被识别");
        assertTrue(result[1], "flv muxer 应被识别");
    }

    @Test
    @DisplayName("banner/表头/分隔行不产生误判")
    void noFalsePositiveOnHeaderLines() {
        String output = """
                ffmpeg version 8.0.1-3ubuntu2 Copyright (c) 2000-2025 the FFmpeg developers
                File formats:
                 D. = Demuxing supported
                 .E = Muxing supported
                 --
                 D  flv             FLV (Flash Video)
                """;
        boolean[] result = FfmpegWhipPusher.parseMuxers(output);
        assertFalse(result[0]);
        assertFalse(result[1], "仅解封装（D）的 flv 行不应判为 RTMP 可用");
    }

    @Test
    @DisplayName("无 flv/whip 的输出返回双 false")
    void parseEmptyCapabilities() {
        boolean[] result = FfmpegWhipPusher.parseMuxers("File formats:\n D. = Demuxing supported\n --\n");
        assertFalse(result[0]);
        assertFalse(result[1]);
    }
}
