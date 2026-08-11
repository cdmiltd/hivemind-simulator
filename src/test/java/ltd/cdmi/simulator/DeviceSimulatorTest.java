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

package ltd.cdmi.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.simulator.config.MqttProperties;
import ltd.cdmi.simulator.config.RuntimeConfig;
import ltd.cdmi.simulator.config.SimulatorProperties;
import ltd.cdmi.simulator.device.DeviceState;
import ltd.cdmi.simulator.device.DeviceSimulator;
import ltd.cdmi.simulator.device.DeviceType;
import ltd.cdmi.simulator.device.Dock1OsdStrategy;
import ltd.cdmi.simulator.device.Dock3OsdBuilder;
import ltd.cdmi.simulator.device.Dock3OsdStrategy;
import ltd.cdmi.simulator.device.M4DDroneOsdBuilder;
import ltd.cdmi.simulator.mqtt.DrcMessage;
import ltd.cdmi.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OSD/DRC 报文 JSON 结构测试。
 */
class DeviceSimulatorTest {

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Device(
                        "SIM-DOCK3-001", "SIM-M4D-001",
                        DeviceType.DOCK3, DeviceType.M4D,
                        "org", "code", "license"),
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000)
        );
    }

    private RuntimeConfig testRuntimeConfig() {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps());
    }

    private DeviceSimulator newSimulator(ObjectMapper objectMapper, DeviceState state) {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        // 注入真实 Builder/Strategy：DeviceSimulator 重构为 Builder 模式后（TDD-SPEC 2.12），
        // OSD 构造委托给 DockOsdBuilder/DroneOsdBuilder，命名风格由 OsdStrategy 提供。
        // 此处按 testProps() 配置的 DOCK3 + M4D 注入对应实现。
        return new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdStrategy(), new Dock1OsdStrategy()),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()));
    }

    @Test
    void dockOsdJsonContainsAllRequiredFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdStrategy(), new Dock1OsdStrategy()),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()));

        // 触发 OSD 上报（公开行为，避免反射调用已重构删除的私有方法）
        simulator.publishOsd();

        // publishOsd 依次发布 Dock OSD 与 Drone OSD，第一次为 Dock OSD
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.times(2)).publish(Mockito.anyString(), payloadCaptor.capture());
        String json = payloadCaptor.getAllValues().get(0);

        JsonNode node = objectMapper.readTree(json);
        assertEquals("dock3", node.path("version").asText());
        assertFalse(node.path("bid").asText().isEmpty());
        assertTrue(node.path("timestamp").asLong() > 0);

        JsonNode data = node.path("data");
        assertTrue(data.has("mode_code"));
        assertTrue(data.has("cover_state"));
        assertTrue(data.has("drone_in_dock"));
        assertTrue(data.has("drone_charge_state"));
        assertTrue(data.has("temperature"));
        assertTrue(data.has("humidity"));
        assertTrue(data.has("wind_speed"));
        assertTrue(data.has("rainfall"));
        assertTrue(data.has("latitude"));
        assertTrue(data.has("longitude"));
        assertTrue(data.has("height"));
        assertTrue(data.has("backup_battery"));
        assertTrue(data.has("network_state"));
        assertTrue(data.has("storage"));
        assertTrue(data.has("sub_device"));

        // sub_device 应包含无人机 SN 和型号
        JsonNode subDevice = data.path("sub_device");
        assertEquals("SIM-M4D-001", subDevice.path("device_sn").asText());
        assertEquals("0-100-0", subDevice.path("device_model_key").asText());
    }

    @Test
    void droneOsdJsonContainsAllRequiredFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdStrategy(), new Dock1OsdStrategy()),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()));

        simulator.publishOsd();

        // 第二次 publish 为 Drone OSD
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.times(2)).publish(Mockito.anyString(), payloadCaptor.capture());
        String json = payloadCaptor.getAllValues().get(1);

        JsonNode node = objectMapper.readTree(json);
        assertEquals("dock3", node.path("version").asText());

        JsonNode data = node.path("data");
        assertTrue(data.has("mode_code"));
        assertTrue(data.has("latitude"));
        assertTrue(data.has("longitude"));
        assertTrue(data.has("height"));
        assertTrue(data.has("altitude"));
        assertTrue(data.has("elevation"));
        assertTrue(data.has("attitude_pitch"));
        assertTrue(data.has("attitude_roll"));
        assertTrue(data.has("attitude_head"));
        assertTrue(data.has("horizontal_speed"));
        assertTrue(data.has("vertical_speed"));
        assertTrue(data.has("battery"));
        assertTrue(data.has("position_state"));
        assertTrue(data.has("control_mode"));
        assertTrue(data.has("flight_time"));

        // battery 子结构
        JsonNode battery = data.path("battery");
        assertTrue(battery.has("capacity_percent"));
        assertTrue(battery.has("remain_flight_time"));
        assertTrue(battery.has("voltage"));
        assertTrue(battery.has("temperature"));
    }

    @Test
    void drcCameraOsdInfoPushFormat() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceSimulator simulator = newSimulator(objectMapper, new DeviceState());

        // 调用 buildDrcCameraOsdInfo()
        Method method = DeviceSimulator.class.getDeclaredMethod("buildDrcCameraOsdInfo");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) method.invoke(simulator);

        // 用 DrcMessage.event 包装
        Map<String, Object> drcMsg = DrcMessage.event("drc_camera_osd_info_push", data);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(drcMsg);

        // 打印完整消息
        System.out.println("=== drc_camera_osd_info_push 完整消息 ===");
        System.out.println(json);
        System.out.println("=== 消息结束 ===");

        // 验证 DRC 消息格式 {method, data, seq}
        JsonNode node = objectMapper.readTree(json);
        assertEquals("drc_camera_osd_info_push", node.path("method").asText());
        assertTrue(node.path("seq").asInt() > 0);

        // 验证 6 个顶层字段
        JsonNode d = node.path("data");
        assertTrue(d.has("payload_index"));
        assertTrue(d.has("wide_lense"));
        assertTrue(d.has("zoom_lense"));
        assertTrue(d.has("measure_target"));
        assertTrue(d.has("ir_lense"));
        assertTrue(d.has("liveview"));

        // 验证 payload_index 格式 {type-subtype-gimbalindex}
        String payloadIndex = d.path("payload_index").asText();
        assertTrue(payloadIndex.matches("\\d+-\\d+-\\d+"), "payload_index 格式应为 {type-subtype-gimbalindex}");

        // 验证 zoom_lense 关键字段
        JsonNode zoomLense = d.path("zoom_lense");
        assertTrue(zoomLense.has("zoom_factor"));
        assertTrue(zoomLense.has("zoom_focus_value"));
        assertTrue(zoomLense.has("zoom_exposure_mode"));

        // 验证 ir_lense 关键字段
        JsonNode irLense = d.path("ir_lense");
        assertTrue(irLense.has("thermal_global_temperature_min"));
        assertTrue(irLense.has("thermal_global_temperature_max"));
        assertTrue(irLense.path("thermal_global_temperature_min").asDouble()
                < irLense.path("thermal_global_temperature_max").asDouble(),
                "最低温度应小于最高温度");

        // 验证 measure_target 关键字段
        JsonNode measureTarget = d.path("measure_target");
        assertTrue(measureTarget.has("measure_target_distance"));
        assertTrue(measureTarget.has("measure_target_longitude"));
        assertTrue(measureTarget.has("measure_target_latitude"));

        // 验证 liveview 关键字段
        JsonNode liveview = d.path("liveview");
        assertTrue(liveview.has("liveview_world_region"));
        JsonNode region = liveview.path("liveview_world_region");
        assertTrue(region.has("left"));
        assertTrue(region.has("top"));
        assertTrue(region.has("right"));
        assertTrue(region.has("bottom"));
    }
}
