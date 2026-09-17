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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 运行时可变的连接配置持有者。
 * <p>启动时从 {@link SimulatorProperties} 初始化，Web 控制台可通过 REST API 在运行时修改，
 * 供 {@link ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager}（MQTT 连接参数）和
 * {@link ltd.cdmi.hivemind.simulator.device.DockOnlineService}（组织ID/绑定码/设备类型）读取最新值。</p>
 * <p>所有字段声明 volatile，保证多线程读写可见性。</p>
 * <p>live 推流 + 媒体上传配置通过 {@link LiveConfigStore} 持久化到本地文件，应用重启后自动恢复，
 * 覆盖 yml 默认值。配置链路：yml → Properties → RuntimeConfig ← LiveConfigStore（持久化覆盖）。</p>
 * <p>SN 生成机制（设计文档 §7.1，TDD-SPEC TC-REG-015/028~031）：默认模式 SN 取设备型号
 * {@link DeviceModelProvider#defaultSn()}；唯一 SN 模式（{@code simulator.sn.unique-enabled=true}）
 * 由 {@link SnGenerator} 生成实例唯一 SN，型号与 SN 成对持久化到 LiveConfigStore，
 * 重启恢复、切换型号重新生成（幂等）。</p>
 */
@Component
public class RuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

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

    /** 唯一 SN 模式（simulator.sn.unique-enabled，设计文档 §7.1）：生成并持久化实例唯一 SN */
    private final boolean snUniqueEnabled;

    /** SN 手动覆盖（TC-REG-032~036）：null 表示未覆盖，由机型派生（defaultSn 或唯一生成 SN） */
    private volatile String dockSnOverride;
    private volatile String droneSnOverride;

    /** SN 覆盖合法字符集（SN 拼入 MQTT topic，禁止 / + # 与空白） */
    private static final Pattern SN_OVERRIDE_PATTERN = Pattern.compile("[0-9A-Za-z_-]{1,32}");

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
        // 唯一 SN 模式（simulator.sn.unique-enabled，设计文档 §7.1）
        SimulatorProperties.Sn snConfig = props.sn();
        this.snUniqueEnabled = snConfig != null && snConfig.uniqueEnabled();

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

        // 唯一 SN 模式：恢复持久化的型号与 SN，或首次生成并立即持久化（TC-REG-028/029/031）
        if (snUniqueEnabled) {
            boolean dockRestored = restoreDockIdentity(saved);
            boolean droneRestored = restoreDroneIdentity(saved);
            if (!dockRestored || !droneRestored) {
                persistLiveConfig();
            }
        }

        // SN 手动覆盖恢复（TC-REG-033）：null 视为未覆盖（旧格式文件向后兼容），恢复后立即持久化
        if (saved != null) {
            boolean overrideRestored = false;
            if (saved.dockSnOverride() != null) {
                this.dockSnOverride = saved.dockSnOverride();
                overrideRestored = true;
            }
            if (saved.droneSnOverride() != null) {
                this.droneSnOverride = saved.droneSnOverride();
                overrideRestored = true;
            }
            if (overrideRestored) {
                log.info("SN 手动覆盖已恢复（dockSnOverride={}, droneSnOverride={}）",
                        dockSnOverride != null ? "是" : "否", droneSnOverride != null ? "是" : "否");
                persistLiveConfig();
            }
        }
    }

    /**
     * 唯一 SN 模式：恢复持久化的机场型号与 SN；无有效持久化值时生成新 SN 并返回 false。
     * <p>型号与 SN 成对恢复，防止"SN 是 DOCK1 格式但型号复位为默认 DOCK3"的不一致（TC-REG-029）；
     * 持久化型号无法识别（如 SDK 枚举变更）时降级为按当前型号重新生成（TC-REG-031 容错）。</p>
     */
    private boolean restoreDockIdentity(LiveConfigStore.LiveConfig saved) {
        if (saved != null && saved.dockType() != null && saved.dockSn() != null) {
            try {
                this.dockType = DockModel.valueOf(saved.dockType());
                this.dockSn = saved.dockSn();
                return true;
            } catch (IllegalArgumentException e) {
                log.warn("持久化的机场型号无法识别（{}），按当前型号重新生成唯一 SN", saved.dockType());
            }
        }
        this.dockSn = SnGenerator.uniqueFor(this.dockType);
        log.info("唯一 SN 模式：生成机场 SN（dockType={}, dockSn={}）", dockType, dockSn);
        return false;
    }

    /**
     * 唯一 SN 模式：恢复持久化的飞行器型号与 SN；逻辑同 {@link #restoreDockIdentity}。
     */
    private boolean restoreDroneIdentity(LiveConfigStore.LiveConfig saved) {
        if (saved != null && saved.droneType() != null && saved.droneSn() != null) {
            try {
                this.droneType = DroneModel.valueOf(saved.droneType());
                this.droneSn = saved.droneSn();
                return true;
            } catch (IllegalArgumentException e) {
                log.warn("持久化的飞行器型号无法识别（{}），按当前型号重新生成唯一 SN", saved.droneType());
            }
        }
        this.droneSn = SnGenerator.uniqueFor(this.droneType);
        log.info("唯一 SN 模式：生成飞行器 SN（droneType={}, droneSn={}）", droneType, droneSn);
        return false;
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

    /**
     * 切换机场型号并同步更新 SN。
     * <p>默认模式：SN 取 defaultSn()（TC-REG-015）；
     * 唯一 SN 模式：型号实际变化时重新生成唯一 SN 并立即持久化，重复设置相同型号保持 SN 不变
     * （幂等，防 /connect 重复提交导致设备身份漂移，TC-REG-030）。</p>
     */
    public void setDockType(DockModel dockType) {
        boolean changed = dockType != this.dockType;
        this.dockType = dockType;
        if (snUniqueEnabled) {
            if (!changed) {
                return;
            }
            this.dockSn = SnGenerator.uniqueFor(dockType);
            persistLiveConfig();
        } else {
            this.dockSn = dockType.defaultSn();
        }
    }

    public DroneModel getDroneType() { return droneType; }

    /**
     * 切换飞行器型号并同步更新 SN，逻辑同 {@link #setDockType(DockModel)}。
     */
    public void setDroneType(DroneModel droneType) {
        boolean changed = droneType != this.droneType;
        this.droneType = droneType;
        if (snUniqueEnabled) {
            if (!changed) {
                return;
            }
            this.droneSn = SnGenerator.uniqueFor(droneType);
            persistLiveConfig();
        } else {
            this.droneSn = droneType.defaultSn();
        }
    }

    /**
     * 生效机场 SN（三级优先级，TC-REG-032）：手动覆盖 > 唯一模式生成 SN > 机型 defaultSn。
     */
    public String getDockSn() { return dockSnOverride != null ? dockSnOverride : dockSn; }

    /**
     * 生效飞行器 SN（三级优先级，TC-REG-032）：手动覆盖 > 唯一模式生成 SN > 机型 defaultSn。
     */
    public String getDroneSn() { return droneSnOverride != null ? droneSnOverride : droneSn; }

    /** 当前机场 SN 覆盖值（null 表示未覆盖，生效 SN 由机型派生） */
    public String getDockSnOverride() { return dockSnOverride; }

    /** 当前飞行器 SN 覆盖值（null 表示未覆盖，生效 SN 由机型派生） */
    public String getDroneSnOverride() { return droneSnOverride; }

    /**
     * 设置机场 SN 手动覆盖（TC-REG-032/034/035）。
     *
     * @param sn 覆盖值；null 或空串清除覆盖（回退机型派生值）；非法格式返回 false 不生效
     * @return 是否设置成功（校验失败返回 false，原因由调用方通过消息提示）
     */
    public boolean setDockSnOverride(String sn) {
        String validated = validateSnOverride(sn);
        if (validated == null && sn != null && !sn.isBlank()) {
            return false;
        }
        this.dockSnOverride = validated;
        persistLiveConfig();
        return true;
    }

    /**
     * 设置飞行器 SN 手动覆盖，逻辑同 {@link #setDockSnOverride(String)}。
     */
    public boolean setDroneSnOverride(String sn) {
        String validated = validateSnOverride(sn);
        if (validated == null && sn != null && !sn.isBlank()) {
            return false;
        }
        this.droneSnOverride = validated;
        persistLiveConfig();
        return true;
    }

    /**
     * 校验并归一化 SN 覆盖值（TC-REG-035）：非空时须匹配 {@code [0-9A-Za-z_-]{1,32}}
     * （SN 拼入 MQTT topic，禁止 / + # 与空白）；null/空白归一化为 null（清除覆盖）。
     */
    private String validateSnOverride(String sn) {
        if (sn == null || sn.isBlank()) {
            return null;
        }
        String trimmed = sn.trim();
        return SN_OVERRIDE_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    /** 唯一 SN 模式是否启用（simulator.sn.unique-enabled，设计文档 §7.1） */
    public boolean isSnUniqueEnabled() { return snUniqueEnabled; }

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
     * 将当前 live + media + location + pilot + 唯一 SN 配置持久化到文件。
     * <p>由 SimulatorController 在配置变更后调用（唯一 SN 模式下生成/切换 SN 时也调用），
     * 确保重启后可恢复。写入失败仅告警日志，不影响内存配置。
     * 唯一 SN 模式关闭时 sn 字段传 null（不写入文件，保持默认模式文件格式不变）。</p>
     */
    public void persistLiveConfig() {
        liveConfigStore.save(liveRealPushEnabled, liveFfmpegPath, liveVideoDir, mediaDir,
                locationLatitude, locationLongitude, locationHeight,
                hivemindHttpBaseUrl, hivemindHttpToken, hivemindWsUrl, hivemindWsToken,
                snUniqueEnabled ? dockType.name() : null,
                snUniqueEnabled ? droneType.name() : null,
                snUniqueEnabled ? dockSn : null,
                snUniqueEnabled ? droneSn : null,
                dockSnOverride, droneSnOverride);
    }
}
