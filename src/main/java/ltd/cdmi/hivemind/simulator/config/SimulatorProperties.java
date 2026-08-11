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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模拟器配置项（绑定 application.yml 中 simulator.* 配置）。
 * <p>MQTT 连接配置已提升为公共配置，见 {@link MqttProperties}。</p>
 * <p>设备型号 / SN / 组织ID / 绑定码 / DJI License 均由用户在注册时通过前端表单输入，
 * 不在此配置。RuntimeConfig 提供默认设备型号（DOCK3 + M4TD）。</p>
 */
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(
        Location location,
        Log log,
        Live live,
        Media media
) {

    /** 机场默认位置（默认成都，与 hivemind 默认地图中心一致） */
    public record Location(
            double latitude,
            double longitude,
            double height
    ) {}

    /** 指令通讯窗口日志配置 */
    public record Log(
            int maxSize
    ) {}

    /**
     * 直播推流配置。
     * <p>默认仅协议模拟（real-push-enabled=false）；启用后需配置 ffmpeg-path 和 video-dir。
     * <p>仅支持 WebRTC (url_type=4)，RTMP/GB28181 降级为协议模拟。
     */
    public record Live(
            boolean realPushEnabled,
            String ffmpegPath,
            String videoDir
    ) {}

    /**
     * 媒体上传配置。
     * <p>media-dir 配置模拟照片/视频文件目录，媒体上传时从该目录读取文件上传到对象存储。
     * <p>留空则跳过文件上传，仅上报元数据（file_upload_callback）。
     */
    public record Media(
            String mediaDir
    ) {}
}
