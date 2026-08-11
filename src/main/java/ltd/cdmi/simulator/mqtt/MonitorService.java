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

package ltd.cdmi.simulator.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ltd.cdmi.simulator.config.MqttProperties;
import ltd.cdmi.simulator.config.SimulatorProperties;
import ltd.cdmi.simulator.device.DeviceType;
import ltd.cdmi.simulator.diagnostic.DiagnosticCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控器服务：管理 MQTT 客户端、设备列表、OSD 缓存、指令下发。
 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final ObjectMapper objectMapper;
    private final SimulatorProperties props;
    private final MqttProperties mqttProps;

    private volatile MonitorMqttClient mqttClient;

    /** 设备列表：key = gatewaySn */
    private final Map<String, DeviceInfo> devices = new ConcurrentHashMap<>();

    /** OSD 缓存：key = deviceSn（机场或无人机），value = 最新 OSD 数据 */
    private final Map<String, Map<String, Object>> osdCache = new ConcurrentHashMap<>();

    /** DRC 缓存：key = deviceSn，value = 最新 DRC 数据 */
    private final Map<String, Map<String, Object>> drcCache = new ConcurrentHashMap<>();

    /** 当前选中的设备 SN */
    private volatile String selectedDeviceSn;

    public MonitorService(ObjectMapper objectMapper, SimulatorProperties props, MqttProperties mqttProps) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.mqttProps = mqttProps;
    }

    // ==================== 连接管理 ====================

    /**
     * 连接到第三方平台 MQTT。
     * @return 诊断码：null=成功；{@link DiagnosticCode#PLATFORM_HOST_UNREACHABLE}=地址不可达；{@link DiagnosticCode#PLATFORM_AUTH_FAILED}=认证失败
     */
    public synchronized DiagnosticCode connect(String host, int port, String username, String password) {
        // 参数为空时用 mqtt 公共配置作默认值（前端可覆盖）
        String actualHost = (host == null || host.isEmpty()) ? mqttProps.host() : host;
        int actualPort = (port <= 0) ? mqttProps.port() : port;
        String actualUsername = (username == null || username.isEmpty()) ? mqttProps.username() : username;
        String actualPassword = (password == null || password.isEmpty()) ? mqttProps.password() : password;

        if (mqttClient != null) {
            mqttClient.disconnect();
        }
        int maxLogSize = (props.log() != null && props.log().maxSize() > 0) ? props.log().maxSize() : 2000;
        mqttClient = new MonitorMqttClient(objectMapper, this::handleMessage, maxLogSize);
        return mqttClient.connect(actualHost, actualPort, actualUsername, actualPassword, mqttProps.monitorClientIdPrefix());
    }

    /** 获取 MQTT 默认连接配置（供前端预填，密码脱敏不回传） */
    public Map<String, Object> getDefaultMqttConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("host", mqttProps.host());
        config.put("port", mqttProps.port());
        config.put("username", mqttProps.username());
        config.put("password", "");
        return config;
    }

    /** 断开连接 */
    public synchronized void disconnect() {
        if (mqttClient != null) {
            mqttClient.disconnect();
            mqttClient = null;
        }
        devices.clear();
        osdCache.clear();
        drcCache.clear();
        selectedDeviceSn = null;
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }

    @PreDestroy
    public void destroy() {
        disconnect();
    }

    // ==================== 消息处理 ====================

    private void handleMessage(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String method = node.path("method").asText("");

            // 从 topic 提取设备 SN（thing/product/{sn}/xxx 或 sys/product/{sn}/xxx）
            String sn = extractSn(topic);
            if (sn == null) return;

            if (topic.endsWith("/status")) {
                // update_topo：设备上下线
                handleUpdateTopo(sn, node);
            } else if (topic.endsWith("/osd")) {
                // OSD 遥测数据
                osdCache.put(sn, objectMapper.treeToValue(node, Map.class));
                // 自动发现设备：监控器连接时设备已在线、错过 update_topo 的场景
                JsonNode osdData = node.path("data");
                if (osdData.has("drone_in_dock")) {
                    autoDiscoverFromDockOsd(sn, node);
                }
            } else if (topic.endsWith("/state")) {
                // 状态变更（state 属性合并到 OSD 的 data 字段内部，与 OSD 结构保持一致）
                // DJI Cloud API：osd/state 消息体均为 {tid,bid,data:{...},timestamp,version}，
                // 实际属性在 data 字段内。前端统一从 data 字段读取，故 state 属性须合并到 data 内。
                JsonNode stateData = node.path("data");
                if (!stateData.isMissingNode()) {
                    Map<String, Object> state = objectMapper.treeToValue(stateData, Map.class);
                    osdCache.compute(sn, (k, existing) -> {
                        Map<String, Object> base = existing != null ? existing : new LinkedHashMap<>();
                        Object dataField = base.get("data");
                        if (!(dataField instanceof Map)) {
                            dataField = new LinkedHashMap<>();
                            base.put("data", dataField);
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> dataMap = (Map<String, Object>) dataField;
                        dataMap.putAll(state);
                        return base;
                    });
                }
            } else if (topic.endsWith("/drc/up")) {
                // DRC 上行通道（DRC 模式下的实时状态推送）
                drcCache.put(sn, objectMapper.treeToValue(node, Map.class));
            }
        } catch (Exception e) {
            log.debug("监控器消息解析异常 topic={}: {}", topic, e.getMessage());
        }
    }

    /** 从 topic 提取设备 SN */
    private String extractSn(String topic) {
        // sys/product/{sn}/status → {sn}
        // thing/product/{sn}/osd → {sn}
        String[] parts = topic.split("/");
        if (parts.length >= 4 && ("product".equals(parts[1]))) {
            return parts[2];
        }
        return null;
    }

    /**
     * 从 dock OSD 自动发现设备（用于监控器连接时设备已在线、错过 update_topo 的场景）。
     * 仅填充缺失字段，不覆盖 update_topo 已设置的值。
     */
    private void autoDiscoverFromDockOsd(String dockSn, JsonNode node) {
        JsonNode osdData = node.path("data");
        DeviceInfo info = devices.computeIfAbsent(dockSn, k -> new DeviceInfo());
        boolean isNew = info.gatewaySn == null;
        if (isNew) {
            info.gatewaySn = dockSn;
            log.info("监控器从 OSD 自动发现设备: dock={}", dockSn);
        }
        info.online = true;
        info.lastUpdate = System.currentTimeMillis();

        // 从 sub_device 提取无人机信息
        JsonNode subDevice = osdData.path("sub_device");
        if (!subDevice.isMissingNode()) {
            String droneSn = subDevice.path("device_sn").asText("");
            if (!droneSn.isEmpty() && (info.droneSn == null || info.droneSn.isEmpty())) {
                info.droneSn = droneSn;
            }
            String droneModelKey = subDevice.path("device_model_key").asText("");
            if (!droneModelKey.isEmpty() && info.droneType == 0) {
                String[] parts = droneModelKey.split("-");
                if (parts.length >= 2) {
                    try {
                        info.droneType = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {}
                }
                if (parts.length >= 3) {
                    try {
                        info.droneSubType = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // 从 version 字段推断机场类型（仅当未通过 update_topo 获取时）
        if (info.gatewayType == 0) {
            String version = node.path("version").asText("");
            if (version.contains("dock3")) info.gatewayType = 3;
            else if (version.contains("dock2")) info.gatewayType = 2;
            else if (version.contains("dock")) info.gatewayType = 1;
        }
    }

    /** 处理 update_topo 消息 */
    private void handleUpdateTopo(String gatewaySn, JsonNode node) {
        JsonNode data = node.path("data");
        JsonNode subDevices = data.path("sub_devices");

        if (subDevices.isArray() && subDevices.size() > 0) {
            // 上线：解析子设备
            JsonNode sub = subDevices.get(0);
            String droneSn = sub.path("sn").asText("");
            int droneType = sub.path("type").asInt(0);
            int droneSubType = sub.path("sub_type").asInt(0);
            int gatewayType = data.path("type").asInt(0);

            DeviceInfo info = devices.computeIfAbsent(gatewaySn, k -> new DeviceInfo());
            info.gatewaySn = gatewaySn;
            info.gatewayType = gatewayType;
            info.droneSn = droneSn;
            info.droneType = droneType;
            info.droneSubType = droneSubType;
            info.online = true;
            info.lastUpdate = System.currentTimeMillis();
            log.info("监控器发现设备上线: gateway={}, drone={}", gatewaySn, droneSn);
        } else {
            // 下线
            DeviceInfo info = devices.get(gatewaySn);
            if (info != null) {
                info.online = false;
                info.lastUpdate = System.currentTimeMillis();
                log.info("监控器发现设备下线: gateway={}", gatewaySn);
            }
        }
    }

    // ==================== 设备管理 ====================

    /** 获取设备列表 */
    public List<DeviceInfo> getDevices() {
        return new ArrayList<>(devices.values());
    }

    /** 获取选中设备的 OSD 数据 */
    public Map<String, Object> getOsdData(String sn) {
        return osdCache.getOrDefault(sn, Collections.emptyMap());
    }

    /** 获取所有 OSD 缓存 */
    public Map<String, Map<String, Object>> getAllOsd() {
        return new ConcurrentHashMap<>(osdCache);
    }

    /** 获取 DRC 数据 */
    public Map<String, Object> getDrcData(String sn) {
        return drcCache.getOrDefault(sn, Collections.emptyMap());
    }

    /**
     * 获取选中设备的完整遥测数据（无人机 OSD + 机场 OSD + DRC OSD）。
     * @return 包含 drone_osd、dock_osd、drc_osd 三个字段的 Map
     */
    public Map<String, Object> getTelemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        String gatewaySn = selectedDeviceSn;
        if (gatewaySn == null) {
            result.put("drone_osd", Collections.emptyMap());
            result.put("dock_osd", Collections.emptyMap());
            result.put("drc_osd", Collections.emptyMap());
            return result;
        }
        // 机场 OSD（按 gateway SN 查找）
        result.put("dock_osd", osdCache.getOrDefault(gatewaySn, Collections.emptyMap()));
        // 无人机 OSD（按 drone SN 查找）
        DeviceInfo info = devices.get(gatewaySn);
        String droneSn = info != null ? info.droneSn : null;
        result.put("drone_osd", droneSn != null ? osdCache.getOrDefault(droneSn, Collections.emptyMap()) : Collections.emptyMap());
        // DRC OSD（按 gateway SN 查找，DRC 上行通道使用 gateway SN）
        result.put("drc_osd", drcCache.getOrDefault(gatewaySn, Collections.emptyMap()));
        return result;
    }

    /** 选择设备 */
    public void selectDevice(String sn) {
        this.selectedDeviceSn = sn;
    }

    /** 获取选中设备 SN */
    public String getSelectedDevice() {
        return selectedDeviceSn;
    }

    // ==================== 指令下发 ====================

    /**
     * 下发服务指令到设备。
     * @param sn 设备 SN
     * @param method 指令方法（如 cover_open、one_key_takeoff）
     * @param data 指令数据
     */
    public void sendCommand(String sn, String method, Object data) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            throw new IllegalStateException("监控器未连接");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", method);
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("data", data != null ? data : Collections.emptyMap());

        String topic = TopicConstants.topic(TopicConstants.SERVICES, sn);
        try {
            String json = objectMapper.writeValueAsString(payload);
            mqttClient.publish(topic, json);
            log.info("监控器下发指令: sn={}, method={}", sn, method);
        } catch (Exception e) {
            log.error("监控器下发指令失败: {}", e.getMessage());
        }
    }

    /**
     * 下发属性设置到设备（property/set）。
     * <p>用于设置 accessMode=rw 的属性，如 silent_mode。</p>
     * @param sn 设备 SN
     * @param data 属性键值对（如 {"silent_mode": 1}）
     */
    public void sendPropertySet(String sn, Map<String, Object> data) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            throw new IllegalStateException("监控器未连接");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tid", UUID.randomUUID().toString());
        payload.put("bid", UUID.randomUUID().toString());
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("data", data != null ? data : Collections.emptyMap());

        String topic = TopicConstants.topic(TopicConstants.PROPERTY_SET, sn);
        try {
            String json = objectMapper.writeValueAsString(payload);
            mqttClient.publish(topic, json);
            log.info("监控器下发属性设置: sn={}, data={}", sn, data);
        } catch (Exception e) {
            log.error("监控器下发属性设置失败: {}", e.getMessage());
        }
    }

    // ==================== 日志 ====================

    public List<Map<String, Object>> getLogs() {
        return mqttClient != null ? mqttClient.getLogs() : Collections.emptyList();
    }

    public void clearLogs() {
        if (mqttClient != null) mqttClient.clearLogs();
    }

    // ==================== 设备信息类 ====================

    public static class DeviceInfo {
        public String gatewaySn;
        public int gatewayType;
        public String droneSn;
        public int droneType;
        public int droneSubType;
        public boolean online;
        public long lastUpdate;

        public String getDeviceName() {
            DeviceType dock = DeviceType.fromDockType(gatewayType);
            return dock != null ? dock.getDisplayName() : "Dock-" + gatewayType;
        }

        public String getDroneName() {
            DeviceType drone = DeviceType.fromAircraftType(droneType, droneSubType);
            return drone != null ? drone.getDisplayName() : "Drone-" + droneType;
        }
    }
}
