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

package ltd.cdmi.hivemind.simulator.web;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.device.PilotOnlineService;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.mqtt.MonitorService;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 监控器模式 REST API。
 * <p>作为第三方监控端连接到 DJI Cloud API 平台，监听设备数据、下发控制指令。</p>
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService monitorService;
    private final DockOnlineService dockOnlineService;
    private final PilotOnlineService pilotOnlineService;
    private final RuntimeConfig runtimeConfig;
    private final ObjectMapper objectMapper;

    public MonitorController(MonitorService monitorService,
                             DockOnlineService dockOnlineService,
                             PilotOnlineService pilotOnlineService,
                             RuntimeConfig runtimeConfig,
                             ObjectMapper objectMapper) {
        this.monitorService = monitorService;
        this.dockOnlineService = dockOnlineService;
        this.pilotOnlineService = pilotOnlineService;
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
    }

    /** 连接到第三方平台 MQTT */
    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestBody Map<String, Object> body) {
        String host = String.valueOf(body.getOrDefault("host", "")).trim();
        int port = parsePort(body.get("port"));
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String password = String.valueOf(body.getOrDefault("password", ""));

        DiagnosticCode code = monitorService.connect(host, port, username, password);
        // 连接成功后，如果模拟器设备已在线，重发 update_topo 供监控器发现设备
        // （监控器后连接时可能错过之前的 update_topo，重发一次获取完整设备拓扑）
        if (code == null) {
            if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
                pilotOnlineService.resendTopo();
            } else {
                dockOnlineService.resendTopo();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", code == null);
        result.put("code", code != null ? code.code() : "0");
        result.put("connected", monitorService.isConnected());
        return result;
    }

    /** 获取 MQTT 默认连接配置（供前端预填，密码脱敏不回传） */
    @GetMapping("/default-config")
    public Map<String, Object> defaultConfig() {
        return monitorService.getDefaultMqttConfig();
    }

    /** 断开连接 */
    @PostMapping("/disconnect")
    public Map<String, Object> disconnect() {
        monitorService.disconnect();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("connected", false);
        return result;
    }

    /** 连接状态 + 设备列表 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", monitorService.isConnected());
        result.put("selected_device", monitorService.getSelectedDevice());
        result.put("devices", monitorService.getDevices());
        return result;
    }

    /** 选择设备 */
    @PostMapping("/select")
    public Map<String, Object> select(@RequestBody Map<String, String> body) {
        String sn = body.get("sn");
        monitorService.selectDevice(sn);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("selected_device", sn);
        return result;
    }

    /** 获取选中设备的 OSD 数据 */
    @GetMapping("/osd")
    public Map<String, Object> getOsd(@RequestParam(required = false) String sn) {
        String targetSn = sn != null ? sn : monitorService.getSelectedDevice();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sn", targetSn);
        result.put("data", monitorService.getOsdData(targetSn));
        return result;
    }

    /** 获取所有设备的 OSD 数据 */
    @GetMapping("/osd-all")
    public Map<String, Object> getAllOsd() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", monitorService.getAllOsd());
        return result;
    }

    /** 获取选中设备的完整遥测数据（无人机 OSD + 机场 OSD + DRC OSD） */
    @GetMapping("/telemetry")
    public Map<String, Object> telemetry() {
        return monitorService.getTelemetry();
    }

    /** 下发控制指令 */
    @PostMapping("/command")
    public Map<String, Object> command(@RequestBody Map<String, Object> body) {
        String sn = String.valueOf(body.getOrDefault("sn", "")).trim();
        String method = String.valueOf(body.getOrDefault("method", "")).trim();
        Object data = body.get("data");

        Map<String, Object> result = new LinkedHashMap<>();
        if (sn.isEmpty() || method.isEmpty()) {
            result.put("success", false);
            result.put("message", "缺少参数 sn 或 method");
            return result;
        }
        try {
            monitorService.sendCommand(sn, method, data);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** 下发属性设置（property/set），用于设置 accessMode=rw 的属性 */
    @PostMapping("/property-set")
    public Map<String, Object> propertySet(@RequestBody Map<String, Object> body) {
        String sn = String.valueOf(body.getOrDefault("sn", "")).trim();
        Object data = body.get("data");

        Map<String, Object> result = new LinkedHashMap<>();
        if (sn.isEmpty()) {
            result.put("success", false);
            result.put("message", "缺少参数 sn");
            return result;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = data != null ? (Map<String, Object>) data : Collections.emptyMap();
            monitorService.sendPropertySet(sn, dataMap);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** MQTT 消息日志 */
    @GetMapping("/logs")
    public Map<String, Object> logs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logs", monitorService.getLogs());
        return result;
    }

    /** 清空日志 */
    @DeleteMapping("/logs")
    public Map<String, Object> clearLogs() {
        monitorService.clearLogs();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 导出 OSD 日志数据（替代外部 PowerShell 脚本，避免 Windows Defender 误报）。
     * <p>从监控器 MQTT 消息日志中过滤指定设备的 OSD 上报数据，返回 {topic, data} 格式。
     * <p>用法：GET /api/monitor/logs/osd-export?sn=7UUXN1Q00A008W&direction=recv&limit=200
     * <p>注意：监控器中 OSD 上报方向为 recv（设备→平台），与模拟器的 send 相反。
     *
     * @param sn        设备 SN（多个用逗号分隔，必填）
     * @param direction 方向过滤（默认 recv，监控器中设备上报为 recv）
     * @param limit     返回条数（默认 200）
     * @return OSD 日志列表，每条含 {topic, data}
     */
    @GetMapping("/logs/osd-export")
    public List<Map<String, Object>> exportOsdLogs(
            @RequestParam String sn,
            @RequestParam(defaultValue = "recv") String direction,
            @RequestParam(defaultValue = "200") int limit) {
        List<String> snList = Arrays.stream(sn.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        List<Map<String, Object>> logs = monitorService.getLogs();
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = logs.size() - 1; i >= 0 && result.size() < limit; i--) {
            Map<String, Object> entry = logs.get(i);
            String entryDirection = String.valueOf(entry.get("direction"));
            String topic = String.valueOf(entry.get("topic"));

            if (!direction.equals(entryDirection)) {
                continue;
            }
            boolean snMatched = snList.stream().anyMatch(topic::contains);
            if (!snMatched) {
                continue;
            }
            // 按 topic 过滤 OSD 数据（topic 以 /osd 结尾），不依赖 payload 字段
            if (!topic.endsWith("/osd")) {
                continue;
            }
            String payload = String.valueOf(entry.get("payload"));
            try {
                JsonNode node = objectMapper.readTree(payload);
                JsonNode data = node.path("data");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("topic", topic);
                item.put("data", objectMapper.treeToValue(data, Object.class));
                result.add(item);
            } catch (Exception e) {
                // payload 非 JSON 或解析失败，跳过
            }
        }

        Collections.reverse(result);
        return result;
    }

    private int parsePort(Object val) {
        if (val == null) return 1883;
        try {
            return Integer.parseInt(String.valueOf(val).trim());
        } catch (Exception e) {
            return 1883;
        }
    }
}
