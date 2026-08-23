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

import ltd.cdmi.dji.cloudapi.sdk.model.DeviceModelProvider;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import org.springframework.stereotype.Component;

/**
 * 运行时可变的连接配置持有者。
 * <p>启动时从 {@link SimulatorProperties} 初始化，Web 控制台可通过 REST API 在运行时修改，
 * 供 {@link ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager}（MQTT 连接参数）和
 * {@link ltd.cdmi.hivemind.simulator.device.DockOnlineService}（组织ID/绑定码/设备类型）读取最新值。</p>
 * <p>所有字段声明 volatile，保证多线程读写可见性。</p>
 * <p>live 推流 + 媒体上传配置通过 {@link LiveConfigStore} 持久化到本地文件，应用重启后自动恢复，
 * 覆盖 yml 默认值。配置链路：yml → Properties → RuntimeConfig ← LiveConfigStore（持久化覆盖）。</p>
 */
@Component
public class RuntimeConfig {

    private volatile String mqttHost;
    private volatile int mqttPort;
    private volatile String mqttUsername;
    private volatile String mqttPassword;
    private volatile String organizationId;
    private volatile String deviceBindingCode;
    private volatile String appLicense;
    private volatile DockModel dockType;
    private volatile DroneModel droneType;
    private volatile String dockSn;
    private volatile String droneSn;

    /** 设备接入模式（Dock to Cloud / Pilot to Cloud），默认 DOCK */
    private volatile DeviceMode deviceMode;
    /** Pilot 模式网关设备类型（遥控器），默认 RC_PLUS */
    private volatile RcModel controllerType;
    /** Pilot 模式网关 SN，由 controllerType 决定，不可手动配置 */
    private volatile String controllerSn;
    /** Pilot 模式用户选择的负载（仅 M350/M300/M400 等可挂载通用云台的机型有意义，null 表示回退默认主相机） */
    private volatile PayloadType selectedPayload;

    /** 直播推流配置（运行时可由前端覆盖，yml 提供默认值，LiveConfigStore 持久化恢复） */
    private volatile boolean liveRealPushEnabled;
    private volatile String liveFfmpegPath;
    private volatile String liveVideoDir;
    /** 直播方式（JSBridge live 模块 video-publish-type）：video-on-demand/video-by-manual/video-demand-aux-manual */
    private volatile String liveVideoPublishType;

    /** 媒体上传配置（运行时可由前端覆盖，yml 提供默认值，LiveConfigStore 持久化恢复） */
    private volatile String mediaDir;
    /** 媒体自动上传配置（JSBridge media 模块参数化，运行时可由前端覆盖） */
    private volatile boolean mediaAutoUploadPhoto;
    private volatile int mediaAutoUploadPhotoType;
    private volatile boolean mediaAutoUploadVideo;
    private volatile int mediaDownloadOwner;

    /** 机场位置（运行时可由前端覆盖，yml 提供默认值，LiveConfigStore 持久化恢复） */
    private volatile double locationLatitude;
    private volatile double locationLongitude;
    private volatile double locationHeight;

    /** hivemind HTTP API 基础地址（Pilot 上云专用，运行时可由前端覆盖） */
    private volatile String hivemindHttpBaseUrl;
    /** hivemind HTTP 请求超时（毫秒） */
    private volatile int hivemindHttpTimeout;
    /** hivemind HTTP 访问令牌（x-auth-token，Pilot 上云专用，运行时可由前端覆盖） */
    private volatile String hivemindHttpToken;
    /** hivemind WebSocket 地址（Pilot 上云专用，运行时可由前端覆盖） */
    private volatile String hivemindWsUrl;
    /** hivemind WebSocket 访问令牌（x-auth-token，Pilot 上云专用，运行时可由前端覆盖） */
    private volatile String hivemindWsToken;
    /** 地图元素归属用户名（Pilot 上云 map 模块，运行时可由前端覆盖） */
    private volatile String mapUserName;
    /** 地图元素名称前缀（Pilot 上云 map 模块，运行时可由前端覆盖） */
    private volatile String mapElementPreName;
    /** MOP（Mission Open Platform）数据传输配置（JSBridge mop 模块，运行时可由前端覆盖） */
    private volatile String mopHost;
    private volatile String mopToken;

    /** 物模型版本号（update_topo thing_version 字段，运行时可由前端覆盖） */
    private volatile String thingVersion;

    private final LiveConfigStore liveConfigStore;

    public RuntimeConfig(MqttProperties mqttProps, SimulatorProperties props, LiveConfigStore liveConfigStore) {
        this.mqttHost = mqttProps.host();
        this.mqttPort = mqttProps.port();
        this.mqttUsername = mqttProps.username();
        this.mqttPassword = mqttProps.password();
        this.organizationId = "";       // 由用户在注册时通过前端表单输入
        this.deviceBindingCode = "";    // 由用户在注册时通过前端表单输入
        this.appLicense = "";           // 由用户在注册时通过前端表单输入
        this.dockType = DockModel.DOCK3;   // 默认设备型号，用户可在注册时切换
        this.droneType = DroneModel.M4TD;   // 默认设备型号，与 DOCK3 兼容
        // SN 完全由设备型号决定，不可手动配置
        this.dockSn = this.dockType.defaultSn();
        this.droneSn = this.droneType.defaultSn();
        // Pilot 模式默认配置（仅 deviceMode=PILOT 时生效）
        this.deviceMode = DeviceMode.DOCK;          // 默认 Dock 模式
        this.controllerType = RcModel.RC_PLUS;   // 默认遥控器型号，用户可在注册时切换
        this.controllerSn = this.controllerType.defaultSn();
        this.selectedPayload = null;  // 默认未选择，OSD 构建时回退 defaultCameraFor()
        this.liveConfigStore = liveConfigStore;

        // yml 提供默认值
        SimulatorProperties.Live live = props.live();
        if (live != null) {
            this.liveRealPushEnabled = live.realPushEnabled();
            this.liveFfmpegPath = live.ffmpegPath();
            this.liveVideoDir = live.videoDir();
            this.liveVideoPublishType = live.videoPublishType() != null ? live.videoPublishType() : "video-on-demand";
        } else {
            this.liveRealPushEnabled = false;
            this.liveFfmpegPath = "ffmpeg";
            this.liveVideoDir = "";
            this.liveVideoPublishType = "video-on-demand";
        }

        // 媒体上传配置（yml 默认值）
        SimulatorProperties.Media media = props.media();
        if (media != null) {
            this.mediaDir = media.mediaDir() != null ? media.mediaDir() : "";
            this.mediaAutoUploadPhoto = media.autoUploadPhoto();
            this.mediaAutoUploadPhotoType = media.autoUploadPhotoType();
            this.mediaAutoUploadVideo = media.autoUploadVideo();
            this.mediaDownloadOwner = media.downloadOwner();
        } else {
            this.mediaDir = "";
            this.mediaAutoUploadPhoto = false;
            this.mediaAutoUploadPhotoType = 0;
            this.mediaAutoUploadVideo = false;
            this.mediaDownloadOwner = 0;
        }

        // 机场位置（yml 默认值）
        SimulatorProperties.Location loc = props.location();
        if (loc != null) {
            this.locationLatitude = loc.latitude();
            this.locationLongitude = loc.longitude();
            this.locationHeight = loc.height();
        } else {
            this.locationLatitude = 30.670815;
            this.locationLongitude = 104.071523;
            this.locationHeight = 500.0;
        }

        // hivemind HTTP/WebSocket 配置（yml 默认值，运行时可由前端覆盖）
        SimulatorProperties.Hivemind hivemind = props.hivemind();
        if (hivemind != null && hivemind.http() != null) {
            this.hivemindHttpBaseUrl = hivemind.http().baseUrl() != null ? hivemind.http().baseUrl() : "";
            this.hivemindHttpTimeout = hivemind.http().timeout() > 0 ? hivemind.http().timeout() : 5000;
            this.hivemindHttpToken = hivemind.http().token() != null ? hivemind.http().token() : "";
        } else {
            this.hivemindHttpBaseUrl = "";
            this.hivemindHttpTimeout = 5000;
            this.hivemindHttpToken = "";
        }
        if (hivemind != null && hivemind.websocket() != null) {
            this.hivemindWsUrl = hivemind.websocket().url() != null ? hivemind.websocket().url() : "";
            this.hivemindWsToken = hivemind.websocket().token() != null ? hivemind.websocket().token() : "";
        } else {
            this.hivemindWsUrl = "";
            this.hivemindWsToken = "";
        }

        // 地图模块配置（yml 默认值，运行时可由前端覆盖）
        SimulatorProperties.Map mapConfig = props.map();
        if (mapConfig != null) {
            this.mapUserName = mapConfig.userName() != null ? mapConfig.userName() : "";
            this.mapElementPreName = mapConfig.elementPreName() != null ? mapConfig.elementPreName() : "";
        } else {
            this.mapUserName = "";
            this.mapElementPreName = "";
        }

        // MOP 数据传输配置（yml 默认值，运行时可由前端覆盖）
        SimulatorProperties.Mop mopConfig = props.mop();
        if (mopConfig != null) {
            this.mopHost = mopConfig.host() != null ? mopConfig.host() : "";
            this.mopToken = mopConfig.token() != null ? mopConfig.token() : "";
        } else {
            this.mopHost = "";
            this.mopToken = "";
        }

        // 物模型版本（yml 默认值，运行时可由前端覆盖）
        SimulatorProperties.Thing thingConfig = props.thing();
        if (thingConfig != null && thingConfig.thingVersion() != null) {
            this.thingVersion = thingConfig.thingVersion();
        } else {
            this.thingVersion = "1.2.3";
        }

        // 持久化文件覆盖 yml 默认值（文件不存在或读取失败时保持 yml 默认值）
        LiveConfigStore.LiveConfig saved = liveConfigStore.load();
        if (saved != null) {
            this.liveRealPushEnabled = saved.realPushEnabled();
            this.liveFfmpegPath = saved.ffmpegPath() != null ? saved.ffmpegPath() : this.liveFfmpegPath;
            this.liveVideoDir = saved.videoDir() != null ? saved.videoDir() : this.liveVideoDir;
            this.mediaDir = saved.mediaDir() != null ? saved.mediaDir() : this.mediaDir;
            // 向后兼容：旧配置文件缺少 location 字段时 Jackson 填充 0.0，三字段同时为 0.0 视为未配置，回退到 yml 默认值
            if (saved.locationLatitude() != 0.0 || saved.locationLongitude() != 0.0 || saved.locationHeight() != 0.0) {
                this.locationLatitude = saved.locationLatitude();
                this.locationLongitude = saved.locationLongitude();
                this.locationHeight = saved.locationHeight();
            }
            // 向后兼容：旧配置文件缺少 pilot 字段时 Jackson 填充 null，null 视为未配置，保持 yml 默认值
            this.hivemindHttpBaseUrl = saved.pilotHttpBaseUrl() != null ? saved.pilotHttpBaseUrl() : this.hivemindHttpBaseUrl;
            this.hivemindHttpToken = saved.pilotHttpToken() != null ? saved.pilotHttpToken() : this.hivemindHttpToken;
            this.hivemindWsUrl = saved.pilotWsUrl() != null ? saved.pilotWsUrl() : this.hivemindWsUrl;
            this.hivemindWsToken = saved.pilotWsToken() != null ? saved.pilotWsToken() : this.hivemindWsToken;
        }
    }

    public String getMqttHost() { return mqttHost; }
    public void setMqttHost(String mqttHost) { this.mqttHost = mqttHost; }

    public int getMqttPort() { return mqttPort; }
    public void setMqttPort(int mqttPort) { this.mqttPort = mqttPort; }

    public String getMqttUsername() { return mqttUsername; }
    public void setMqttUsername(String mqttUsername) { this.mqttUsername = mqttUsername; }

    public String getMqttPassword() { return mqttPassword; }
    public void setMqttPassword(String mqttPassword) { this.mqttPassword = mqttPassword; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getDeviceBindingCode() { return deviceBindingCode; }
    public void setDeviceBindingCode(String deviceBindingCode) { this.deviceBindingCode = deviceBindingCode; }

    public String getAppLicense() { return appLicense; }
    public void setAppLicense(String appLicense) { this.appLicense = appLicense; }

    public DockModel getDockType() { return dockType; }
    public void setDockType(DockModel dockType) {
        this.dockType = dockType;
        this.dockSn = dockType.defaultSn();
    }

    public DroneModel getDroneType() { return droneType; }
    public void setDroneType(DroneModel droneType) {
        this.droneType = droneType;
        this.droneSn = droneType.defaultSn();
    }

    public String getDockSn() { return dockSn; }

    public String getDroneSn() { return droneSn; }

    public DeviceMode getDeviceMode() { return deviceMode; }
    public void setDeviceMode(DeviceMode deviceMode) { this.deviceMode = deviceMode; }

    public RcModel getControllerType() { return controllerType; }
    public void setControllerType(RcModel controllerType) {
        this.controllerType = controllerType;
        this.controllerSn = controllerType.defaultSn();
    }

    public String getControllerSn() { return controllerSn; }

    public PayloadType getSelectedPayload() { return selectedPayload; }
    public void setSelectedPayload(PayloadType selectedPayload) { this.selectedPayload = selectedPayload; }

    /**
     * 获取当前模式的网关 SN。
     * <p>Dock 模式返回 dockSn，Pilot 模式返回 controllerSn。
     * <p>供 MQTT topic 构造、update_topo 等场景使用，避免调用方关心当前模式。
     */
    public String getGatewaySn() {
        return deviceMode == DeviceMode.PILOT ? controllerSn : dockSn;
    }

    /**
     * 获取当前模式的网关设备类型。
     * <p>Dock 模式返回 dockType，Pilot 模式返回 controllerType。
     */
    public DeviceModelProvider getGatewayType() {
        return deviceMode == DeviceMode.PILOT ? controllerType : dockType;
    }

    public boolean isLiveRealPushEnabled() { return liveRealPushEnabled; }
    public void setLiveRealPushEnabled(boolean liveRealPushEnabled) { this.liveRealPushEnabled = liveRealPushEnabled; }

    public String getLiveFfmpegPath() { return liveFfmpegPath; }
    public void setLiveFfmpegPath(String liveFfmpegPath) { this.liveFfmpegPath = liveFfmpegPath; }

    public String getLiveVideoDir() { return liveVideoDir; }
    public void setLiveVideoDir(String liveVideoDir) { this.liveVideoDir = liveVideoDir; }

    public String getLiveVideoPublishType() { return liveVideoPublishType; }
    public void setLiveVideoPublishType(String liveVideoPublishType) { this.liveVideoPublishType = liveVideoPublishType; }

    public String getMediaDir() { return mediaDir; }
    public void setMediaDir(String mediaDir) { this.mediaDir = mediaDir; }

    public boolean isMediaAutoUploadPhoto() { return mediaAutoUploadPhoto; }
    public void setMediaAutoUploadPhoto(boolean mediaAutoUploadPhoto) { this.mediaAutoUploadPhoto = mediaAutoUploadPhoto; }

    public int getMediaAutoUploadPhotoType() { return mediaAutoUploadPhotoType; }
    public void setMediaAutoUploadPhotoType(int mediaAutoUploadPhotoType) { this.mediaAutoUploadPhotoType = mediaAutoUploadPhotoType; }

    public boolean isMediaAutoUploadVideo() { return mediaAutoUploadVideo; }
    public void setMediaAutoUploadVideo(boolean mediaAutoUploadVideo) { this.mediaAutoUploadVideo = mediaAutoUploadVideo; }

    public int getMediaDownloadOwner() { return mediaDownloadOwner; }
    public void setMediaDownloadOwner(int mediaDownloadOwner) { this.mediaDownloadOwner = mediaDownloadOwner; }

    public double getLocationLatitude() { return locationLatitude; }
    public void setLocationLatitude(double locationLatitude) { this.locationLatitude = locationLatitude; }

    public double getLocationLongitude() { return locationLongitude; }
    public void setLocationLongitude(double locationLongitude) { this.locationLongitude = locationLongitude; }

    public double getLocationHeight() { return locationHeight; }
    public void setLocationHeight(double locationHeight) { this.locationHeight = locationHeight; }

    public String getHivemindHttpBaseUrl() { return hivemindHttpBaseUrl; }
    public void setHivemindHttpBaseUrl(String hivemindHttpBaseUrl) { this.hivemindHttpBaseUrl = hivemindHttpBaseUrl; }

    public int getHivemindHttpTimeout() { return hivemindHttpTimeout; }
    public void setHivemindHttpTimeout(int hivemindHttpTimeout) { this.hivemindHttpTimeout = hivemindHttpTimeout; }

    public String getHivemindHttpToken() { return hivemindHttpToken; }
    public void setHivemindHttpToken(String hivemindHttpToken) { this.hivemindHttpToken = hivemindHttpToken; }

    public String getHivemindWsUrl() { return hivemindWsUrl; }
    public void setHivemindWsUrl(String hivemindWsUrl) { this.hivemindWsUrl = hivemindWsUrl; }

    public String getHivemindWsToken() { return hivemindWsToken; }
    public void setHivemindWsToken(String hivemindWsToken) { this.hivemindWsToken = hivemindWsToken; }

    public String getMapUserName() { return mapUserName; }
    public void setMapUserName(String mapUserName) { this.mapUserName = mapUserName; }

    public String getMapElementPreName() { return mapElementPreName; }
    public void setMapElementPreName(String mapElementPreName) { this.mapElementPreName = mapElementPreName; }

    public String getMopHost() { return mopHost; }
    public void setMopHost(String mopHost) { this.mopHost = mopHost; }

    public String getMopToken() { return mopToken; }
    public void setMopToken(String mopToken) { this.mopToken = mopToken; }

    public String getThingVersion() { return thingVersion; }
    public void setThingVersion(String thingVersion) { this.thingVersion = thingVersion; }

    /**
     * 将当前 live + media + location + pilot 配置持久化到文件。
     * <p>由 SimulatorController 在配置变更后调用，确保重启后可恢复。
     * 写入失败仅告警日志，不影响内存配置。</p>
     */
    public void persistLiveConfig() {
        liveConfigStore.save(liveRealPushEnabled, liveFfmpegPath, liveVideoDir, mediaDir,
                locationLatitude, locationLongitude, locationHeight,
                hivemindHttpBaseUrl, hivemindHttpToken, hivemindWsUrl, hivemindWsToken);
    }
}
