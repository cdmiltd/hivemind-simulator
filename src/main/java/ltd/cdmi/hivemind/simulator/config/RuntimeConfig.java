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

import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceType;
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
    private volatile DeviceType dockType;
    private volatile DeviceType droneType;
    private volatile String dockSn;
    private volatile String droneSn;

    /** 设备接入模式（Dock to Cloud / Pilot to Cloud），默认 DOCK */
    private volatile DeviceMode deviceMode;
    /** Pilot 模式网关设备类型（遥控器），默认 RC_PLUS */
    private volatile DeviceType controllerType;
    /** Pilot 模式网关 SN，由 controllerType 决定，不可手动配置 */
    private volatile String controllerSn;

    /** 直播推流配置（运行时可由前端覆盖，yml 提供默认值，LiveConfigStore 持久化恢复） */
    private volatile boolean liveRealPushEnabled;
    private volatile String liveFfmpegPath;
    private volatile String liveVideoDir;

    /** 媒体上传配置（运行时可由前端覆盖，yml 提供默认值，LiveConfigStore 持久化恢复） */
    private volatile String mediaDir;

    /** 机场位置（运行时可由前端覆盖，yml 提供默认值，LiveConfigStore 持久化恢复） */
    private volatile double locationLatitude;
    private volatile double locationLongitude;
    private volatile double locationHeight;

    private final LiveConfigStore liveConfigStore;

    public RuntimeConfig(MqttProperties mqttProps, SimulatorProperties props, LiveConfigStore liveConfigStore) {
        this.mqttHost = mqttProps.host();
        this.mqttPort = mqttProps.port();
        this.mqttUsername = mqttProps.username();
        this.mqttPassword = mqttProps.password();
        this.organizationId = "";       // 由用户在注册时通过前端表单输入
        this.deviceBindingCode = "";    // 由用户在注册时通过前端表单输入
        this.appLicense = "";           // 由用户在注册时通过前端表单输入
        this.dockType = DeviceType.DOCK3;   // 默认设备型号，用户可在注册时切换
        this.droneType = DeviceType.M4TD;   // 默认设备型号，与 DOCK3 兼容
        // SN 完全由设备型号决定，不可手动配置
        this.dockSn = this.dockType.defaultSn();
        this.droneSn = this.droneType.defaultSn();
        // Pilot 模式默认配置（仅 deviceMode=PILOT 时生效）
        this.deviceMode = DeviceMode.DOCK;          // 默认 Dock 模式
        this.controllerType = DeviceType.RC_PLUS;   // 默认遥控器型号，用户可在注册时切换
        this.controllerSn = this.controllerType.defaultSn();
        this.liveConfigStore = liveConfigStore;

        // yml 提供默认值
        SimulatorProperties.Live live = props.live();
        if (live != null) {
            this.liveRealPushEnabled = live.realPushEnabled();
            this.liveFfmpegPath = live.ffmpegPath();
            this.liveVideoDir = live.videoDir();
        } else {
            this.liveRealPushEnabled = false;
            this.liveFfmpegPath = "ffmpeg";
            this.liveVideoDir = "";
        }

        // 媒体上传目录（yml 默认值）
        SimulatorProperties.Media media = props.media();
        this.mediaDir = media != null && media.mediaDir() != null ? media.mediaDir() : "";

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

    public DeviceType getDockType() { return dockType; }
    public void setDockType(DeviceType dockType) {
        this.dockType = dockType;
        this.dockSn = dockType.defaultSn();
    }

    public DeviceType getDroneType() { return droneType; }
    public void setDroneType(DeviceType droneType) {
        this.droneType = droneType;
        this.droneSn = droneType.defaultSn();
    }

    public String getDockSn() { return dockSn; }

    public String getDroneSn() { return droneSn; }

    public DeviceMode getDeviceMode() { return deviceMode; }
    public void setDeviceMode(DeviceMode deviceMode) { this.deviceMode = deviceMode; }

    public DeviceType getControllerType() { return controllerType; }
    public void setControllerType(DeviceType controllerType) {
        this.controllerType = controllerType;
        this.controllerSn = controllerType.defaultSn();
    }

    public String getControllerSn() { return controllerSn; }

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
    public DeviceType getGatewayType() {
        return deviceMode == DeviceMode.PILOT ? controllerType : dockType;
    }

    public boolean isLiveRealPushEnabled() { return liveRealPushEnabled; }
    public void setLiveRealPushEnabled(boolean liveRealPushEnabled) { this.liveRealPushEnabled = liveRealPushEnabled; }

    public String getLiveFfmpegPath() { return liveFfmpegPath; }
    public void setLiveFfmpegPath(String liveFfmpegPath) { this.liveFfmpegPath = liveFfmpegPath; }

    public String getLiveVideoDir() { return liveVideoDir; }
    public void setLiveVideoDir(String liveVideoDir) { this.liveVideoDir = liveVideoDir; }

    public String getMediaDir() { return mediaDir; }
    public void setMediaDir(String mediaDir) { this.mediaDir = mediaDir; }

    public double getLocationLatitude() { return locationLatitude; }
    public void setLocationLatitude(double locationLatitude) { this.locationLatitude = locationLatitude; }

    public double getLocationLongitude() { return locationLongitude; }
    public void setLocationLongitude(double locationLongitude) { this.locationLongitude = locationLongitude; }

    public double getLocationHeight() { return locationHeight; }
    public void setLocationHeight(double locationHeight) { this.locationHeight = locationHeight; }

    /**
     * 将当前 live + media + location 配置持久化到文件。
     * <p>由 SimulatorController 在配置变更后调用，确保重启后可恢复。
     * 写入失败仅告警日志，不影响内存配置。</p>
     */
    public void persistLiveConfig() {
        liveConfigStore.save(liveRealPushEnabled, liveFfmpegPath, liveVideoDir, mediaDir,
                locationLatitude, locationLongitude, locationHeight);
    }
}
