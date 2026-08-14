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
        Media media,
        Hivemind hivemind,
        Mop mop,
        Map map
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
     * <p>支持 RTMP (url_type=1) 和 WebRTC (url_type=4) 真实推流，Agora (url_type=0)/GB28181 (url_type=3) 降级为协议模拟。
     */
    public record Live(
            boolean realPushEnabled,
            String ffmpegPath,
            String videoDir,
            String videoPublishType
    ) {}

    /**
     * 媒体上传配置。
     * <p>media-dir 配置模拟照片/视频文件目录，媒体上传时从该目录读取文件上传到对象存储。
     * <p>留空则跳过文件上传，仅上报元数据（file_upload_callback）。
     * <p>auto-upload-photo / auto-upload-video / download-owner 对应 JSBridge media 模块的自动上传参数。
     */
    public record Media(
            String mediaDir,
            boolean autoUploadPhoto,
            int autoUploadPhotoType,
            boolean autoUploadVideo,
            int downloadOwner
    ) {}

    /**
     * hivemind 云平台 HTTP/WebSocket 接口配置（Pilot 上云专用）。
     * <p>Pilot 上云除 MQTT 外，还通过 HTTP 调用 hivemind 的 Server API（航线管理、地图元素、媒体库等），
     * 通过 WebSocket 接收 hivemind 推送（地图元素变更、态势感知等）。
     * <p>base-url 和 ws-url 先走配置，后续看能否通过 JSBridge 注册获得。
     * <p>token 为 hivemind 访问令牌（x-auth-token），DJI 文档要求 WebSocket 连接 URL 中的 token 需 URLEncode；
     * 留空则使用空串，后续通过 JSBridge 注册或 MQTT 登录流程获得。
     */
    public record Hivemind(
            Http http,
            WebSocket websocket
    ) {
        public record Http(
                String baseUrl,
                int timeout,
                String token
        ) {}
        public record WebSocket(
                String url,
                String token
        ) {}
    }

    /**
     * MOP（Mission Open Platform）数据传输配置，对应 JSBridge mop 模块。
     * <p>Pilot 通过 MOP WebSocket 通道进行通用数据传输（如自定义业务数据上下行）。
     * <p>连接 URL 格式：{@code wss://host?x-auth-token=<urlencoded_token>}（参考 DJI JSBridge mop 模块）。
     * <p>host 留空则跳过连接，token 留空则使用空串，运行时可由前端覆盖。
     */
    public record Mop(
            String host,
            String token
    ) {}

    /**
     * Pilot 上云地图模块配置（JSBridge map 模块参数化）。
     * <p>对应 DJI Pilot 上云 map 模块 JSBridge 配置：
     * <ul>
     *   <li>user-name：地图元素归属用户名（element.user_name）</li>
     *   <li>element-pre-name：地图元素名称前缀，便于在多端共享时区分来源</li>
     * </ul>
     * <p>留空则由前端在调用 map 模块接口时另行指定。
     */
    public record Map(
            String userName,
            String elementPreName
    ) {}
}
