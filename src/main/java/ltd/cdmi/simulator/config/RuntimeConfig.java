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

package ltd.cdmi.simulator.config;

import ltd.cdmi.simulator.device.DeviceType;
import org.springframework.stereotype.Component;

/**
 * 运行时可变的连接配置持有者。
 * <p>启动时从 {@link SimulatorProperties} 初始化，Web 控制台可通过 REST API 在运行时修改，
 * 供 {@link ltd.cdmi.simulator.mqtt.MqttClientManager}（MQTT 连接参数）和
 * {@link ltd.cdmi.simulator.device.DockOnlineService}（组织ID/绑定码/设备类型）读取最新值。</p>
 * <p>所有字段声明 volatile，保证多线程读写可见性。</p>
 * <p>live 推流配置通过 {@link LiveConfigStore} 持久化到本地文件，应用重启后自动恢复，
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

    /** 直播推流配置（运行时可由前端覆盖，yml 提供默认值，LiveConfigStore 持久化恢复） */
    private volatile boolean liveRealPushEnabled;
    private volatile String liveFfmpegPath;
    private volatile String liveVideoDir;

    private final LiveConfigStore liveConfigStore;

    public RuntimeConfig(MqttProperties mqttProps, SimulatorProperties props, LiveConfigStore liveConfigStore) {
        this.mqttHost = mqttProps.host();
        this.mqttPort = mqttProps.port();
        this.mqttUsername = mqttProps.username();
        this.mqttPassword = mqttProps.password();
        this.organizationId = props.device().organizationId();
        this.deviceBindingCode = props.device().deviceBindingCode();
        this.appLicense = props.device().appLicense();
        this.dockType = props.device().dockType();
        this.droneType = props.device().droneType();
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

        // 持久化文件覆盖 yml 默认值（文件不存在或读取失败时保持 yml 默认值）
        LiveConfigStore.LiveConfig saved = liveConfigStore.load();
        if (saved != null) {
            this.liveRealPushEnabled = saved.realPushEnabled();
            this.liveFfmpegPath = saved.ffmpegPath() != null ? saved.ffmpegPath() : this.liveFfmpegPath;
            this.liveVideoDir = saved.videoDir() != null ? saved.videoDir() : this.liveVideoDir;
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
    public void setDockType(DeviceType dockType) { this.dockType = dockType; }

    public DeviceType getDroneType() { return droneType; }
    public void setDroneType(DeviceType droneType) { this.droneType = droneType; }

    public boolean isLiveRealPushEnabled() { return liveRealPushEnabled; }
    public void setLiveRealPushEnabled(boolean liveRealPushEnabled) { this.liveRealPushEnabled = liveRealPushEnabled; }

    public String getLiveFfmpegPath() { return liveFfmpegPath; }
    public void setLiveFfmpegPath(String liveFfmpegPath) { this.liveFfmpegPath = liveFfmpegPath; }

    public String getLiveVideoDir() { return liveVideoDir; }
    public void setLiveVideoDir(String liveVideoDir) { this.liveVideoDir = liveVideoDir; }

    /**
     * 将当前 live 配置持久化到文件。
     * <p>由 SimulatorController 在配置变更后调用，确保重启后可恢复。
     * 写入失败仅告警日志，不影响内存配置。</p>
     */
    public void persistLiveConfig() {
        liveConfigStore.save(liveRealPushEnabled, liveFfmpegPath, liveVideoDir);
    }
}
