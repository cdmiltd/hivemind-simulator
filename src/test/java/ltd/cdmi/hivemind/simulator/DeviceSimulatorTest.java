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

package ltd.cdmi.hivemind.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DeviceSimulator;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.device.osd.Dock3OsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M4DDroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.RcPlusOsdBuilder;
import ltd.cdmi.hivemind.simulator.handler.AiSimulator;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.DrcMessage;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OSD/DRC 报文 JSON 结构测试。
 */
class DeviceSimulatorTest {

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null,
                null
        );
    }

    private RuntimeConfig testRuntimeConfig() {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
    }

    private DeviceSimulator newSimulator(ObjectMapper objectMapper, DeviceState state) {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        // 注入真实 Builder：DeviceSimulator 重构为 Builder 模式后（TDD-SPEC 2.12），
        // OSD 构造委托给 DockOsdBuilder/DroneOsdBuilder，所有 Dock 版本统一使用 snake_case。
        // 此处按 testProps() 配置的 DOCK3 + M4D 注入对应实现。
        return new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, testRuntimeConfig(), new DockTopicSchema()));
    }

    @DisplayName("补充测试：Dock OSD JSON 包含所有必需字段")
    @Test
    void dockOsdJsonContainsAllRequiredFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);

        // dock OSD 分多条推送，captureDockOsdJson 合并所有 dock OSD data 供字段验证
        String json = captureDockOsdJson(objectMapper, state);

        JsonNode node = objectMapper.readTree(json);
        assertFalse(node.path("bid").asText().isEmpty());
        assertFalse(node.path("tid").asText().isEmpty());
        assertFalse(node.path("gateway").asText().isEmpty());
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

        // sub_device 应包含无人机 SN 和型号（RuntimeConfig 默认 droneType=M4TD）
        JsonNode subDevice = data.path("sub_device");
        assertEquals(DroneModel.M4TD.defaultSn(), subDevice.path("device_sn").asText());
        assertEquals(DroneModel.M4TD.modelKey(), subDevice.path("device_model_key").asText());
    }

    @DisplayName("补充测试：Drone OSD JSON 包含所有必需字段")
    @Test
    void droneOsdJsonContainsAllRequiredFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, testRuntimeConfig(), new DockTopicSchema()));

        simulator.publishOsd();

        // publishOsd 依次发布 3 条 Dock OSD + 1 条 Drone OSD，共 4 次
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.times(4)).publish(Mockito.anyString(), payloadCaptor.capture());
        String json = payloadCaptor.getAllValues().get(3);

        JsonNode node = objectMapper.readTree(json);
        assertFalse(node.path("tid").asText().isEmpty());
        assertFalse(node.path("gateway").asText().isEmpty());

        JsonNode data = node.path("data");
        // 字段对齐 DJI M30/M3D/M4D properties 文档（pushMode=0 的 OSD 字段）
        assertTrue(data.has("mode_code"));
        assertTrue(data.has("latitude"));
        assertTrue(data.has("longitude"));
        assertTrue(data.has("height"));       // 绝对高度（椭球面）
        assertTrue(data.has("elevation"));    // 相对起飞点高度
        assertTrue(data.has("attitude_pitch"));
        assertTrue(data.has("attitude_roll"));
        assertTrue(data.has("attitude_head"));
        assertTrue(data.has("horizontal_speed"));
        assertTrue(data.has("vertical_speed"));
        assertTrue(data.has("wind_speed"));
        assertTrue(data.has("wind_direction"));
        assertTrue(data.has("battery"));
        assertTrue(data.has("position_state"));
        assertTrue(data.has("total_flight_time"));
        // TC-BUILDER-014：补齐的共用字段（对齐 DJI M4D/M30 文档 + 真机示例）
        assertTrue(data.has("activation_time"), "应包含 activation_time（飞行器激活时间）");
        assertTrue(data.has("gear"), "应包含 gear（档位）");
        assertTrue(data.has("height_limit"), "应包含 height_limit（限高）");
        assertTrue(data.has("distance_limit_status"), "应包含 distance_limit_status（限远状态，M30/M3D/M4D 共有）");
        assertTrue(data.has("rth_altitude"), "应包含 rth_altitude（返航高度，M30/M3D/M4D 共有）");
        assertTrue(data.has("home_distance"), "应包含 home_distance（距Home距离）");
        assertTrue(data.has("maintain_status"), "应包含 maintain_status（保养信息）");
        assertTrue(data.has("night_lights_state"), "应包含 night_lights_state（夜航灯）");
        assertTrue(data.has("obstacle_avoidance"), "应包含 obstacle_avoidance（避障状态）");
        assertTrue(data.has("storage"), "应包含 storage（存储容量）");
        assertTrue(data.has("total_flight_distance"), "应包含 total_flight_distance（总飞行里程）");
        assertTrue(data.has("total_flight_sorties"), "应包含 total_flight_sorties（总飞行架次）");
        assertTrue(data.has("track_id"), "应包含 track_id（轨迹ID）");
        // 文档中不存在的字段不应出现
        assertFalse(data.has("altitude"));            // 文档无此字段（height + elevation 已覆盖）
        assertFalse(data.has("control_mode"));        // 文档无此字段
        assertFalse(data.has("current_camera_type")); // 文档无此字段
        assertFalse(data.has("camera_index"));        // 文档无此字段（应在 cameras[].payload_index）
        assertFalse(data.has("firmware_version"), "顶层 firmware_version 是 pushMode=1，不应在 OSD（应在 state topic）");

        // battery 子结构（DJI 文档：capacity_percent + remain_flight_time + return_home_power + landing_power + batteries 数组）
        JsonNode battery = data.path("battery");
        assertTrue(battery.has("capacity_percent"));
        assertTrue(battery.has("remain_flight_time"));
        assertTrue(battery.has("return_home_power"));
        assertTrue(battery.has("landing_power"));
        assertTrue(battery.has("batteries"));
        JsonNode batteries = battery.path("batteries");
        assertTrue(batteries.isArray() && batteries.size() > 0);
        assertTrue(batteries.get(0).has("voltage"));
        assertTrue(batteries.get(0).has("temperature"));

        // TC-BUILDER-014：maintain_status 子结构（3 条记录，type=1/2/3）
        JsonNode maintainStatus = data.path("maintain_status");
        JsonNode maintainArray = maintainStatus.path("maintain_status_array");
        assertTrue(maintainArray.isArray() && maintainArray.size() == 3, "maintain_status_array 应有 3 条记录");
        assertEquals(1, maintainArray.get(0).path("last_maintain_type").asInt(), "第1条: 基础保养");
        assertEquals(2, maintainArray.get(1).path("last_maintain_type").asInt(), "第2条: 常规保养");
        assertEquals(3, maintainArray.get(2).path("last_maintain_type").asInt(), "第3条: 深度保养");

        // obstacle_avoidance 子结构（horizon + upside + downside）
        JsonNode obstacleAvoidance = data.path("obstacle_avoidance");
        assertTrue(obstacleAvoidance.has("horizon"));
        assertTrue(obstacleAvoidance.has("upside"));
        assertTrue(obstacleAvoidance.has("downside"));

        // storage 子结构（total + used）
        JsonNode storage = data.path("storage");
        assertTrue(storage.has("total"));
        assertTrue(storage.has("used"));

        // M4D 负载属性（升级方式）：measure_target_* 在 type_subtype_gimbalindex struct 下，不以负载索引为 key
        JsonNode gimbalInfo = data.path("type_subtype_gimbalindex");
        assertTrue(gimbalInfo.isObject(), "应包含 type_subtype_gimbalindex struct");
        assertTrue(gimbalInfo.has("gimbal_pitch"), "type_subtype_gimbalindex 应包含 gimbal_pitch");
        assertTrue(gimbalInfo.has("gimbal_roll"), "type_subtype_gimbalindex 应包含 gimbal_roll");
        assertTrue(gimbalInfo.has("gimbal_yaw"), "type_subtype_gimbalindex 应包含 gimbal_yaw");
        assertTrue(gimbalInfo.has("measure_target_longitude"), "type_subtype_gimbalindex 应包含 measure_target_longitude");
        assertTrue(gimbalInfo.has("measure_target_latitude"), "type_subtype_gimbalindex 应包含 measure_target_latitude");
        assertTrue(gimbalInfo.has("measure_target_altitude"), "type_subtype_gimbalindex 应包含 measure_target_altitude");
        assertTrue(gimbalInfo.has("measure_target_distance"), "type_subtype_gimbalindex 应包含 measure_target_distance");
        assertTrue(gimbalInfo.has("measure_target_error_state"), "type_subtype_gimbalindex 应包含 measure_target_error_state");
        assertEquals(3, gimbalInfo.path("measure_target_error_state").asInt(), "measure_target_error_state 应为 3=NO_SIGNAL");
        assertTrue(gimbalInfo.has("payload_index"), "type_subtype_gimbalindex 应包含 payload_index");
        assertTrue(gimbalInfo.has("zoom_factor"), "type_subtype_gimbalindex 应包含 zoom_factor");
        // M4D 不应以负载索引为 key 上报负载属性（M30 旧版方式）
        assertFalse(data.has("99-0-0"), "M4D 不应以负载索引 99-0-0 为 key 上报负载属性（M30 旧版方式）");

        // M4D OSD 应包含 is_near_area_limit / is_near_height_limit（pushMode=0）
        assertTrue(data.has("is_near_area_limit"), "应包含 is_near_area_limit（限飞区接近状态）");
        assertTrue(data.has("is_near_height_limit"), "应包含 is_near_height_limit（限高接近状态）");

        // wireless_link_topo（pushMode=1）不应在 OSD，应在 state topic
        assertFalse(data.has("wireless_link_topo"), "wireless_link_topo 是 pushMode=1，不应在 OSD（应在 state topic）");
    }

    @DisplayName("补充测试：DRC camera_osd_info_push 消息格式")
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

        // 验证 DRC 消息格式 {method, data, timestamp, seq}
        JsonNode node = objectMapper.readTree(json);
        assertEquals("drc_camera_osd_info_push", node.path("method").asText());
        assertTrue(node.path("timestamp").asLong() > 0);
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

    // ==================== TC-LOC-015：无人机不在舱且未激活时 OSD 不上报 ====================

    /**
     * droneActivated=false 时不推送 Drone OSD，仅推送 Dock OSD。
     */
    @DisplayName("TC-LOC-015：无人机不在舱且未激活时 OSD 不上报")
    @Test
    void droneOsdNotPublishedWhenNotActivated() {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(false);
        state.setDroneInDock(false); // 不在舱 + 未激活
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, testRuntimeConfig(), new DockTopicSchema()));

        simulator.publishOsd();

        // 仅推送 3 次（Dock OSD 分 3 条），Drone OSD 不推送
        Mockito.verify(mqtt, Mockito.times(3)).publish(Mockito.anyString(), Mockito.anyString());
    }

    /**
     * droneActivated=true 时推送 Dock OSD（3 条）+ Drone OSD（1 条），共 4 次。
     */
    @DisplayName("TC-ONLINE-005：飞行器激活状态控制 drone OSD 推送")
    @Test
    void droneOsdPublishedWhenActivated() {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, testRuntimeConfig(), new DockTopicSchema()));

        simulator.publishOsd();

        // 推送 4 次（Dock OSD 3 条 + Drone OSD 1 条）
        Mockito.verify(mqtt, Mockito.times(4)).publish(Mockito.anyString(), Mockito.anyString());
    }

    // ===== TC-BUILDER-013: Dock OSD 分多条推送 =====

    @DisplayName("补充测试：Dock OSD 分多条推送")
    @Test
    void dockOsdPublishedAsMultipleMessages() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(false); // 不推送 drone OSD，便于精确验证 dock OSD 条数
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, testRuntimeConfig(), new DockTopicSchema()));

        simulator.publishOsd();

        // 验证：Dock OSD 分 3 条推送（非 1 条）
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.times(3)).publish(topicCaptor.capture(), jsonCaptor.capture());

        // 所有 3 条都发布到 dock OSD topic
        String dockSn = testRuntimeConfig().getDockSn();
        String dockOsdTopic = "thing/product/" + dockSn + "/osd";
        for (String topic : topicCaptor.getAllValues()) {
            assertEquals(dockOsdTopic, topic);
        }

        // 每条消息有独立 envelope（bid/tid 不同）
        Set<String> bids = new java.util.HashSet<>();
        for (String json : jsonCaptor.getAllValues()) {
            JsonNode node = objectMapper.readTree(json);
            bids.add(node.path("bid").asText());
            assertTrue(node.path("timestamp").asLong() > 0);
            assertEquals(dockSn, node.path("gateway").asText());
        }
        assertEquals(3, bids.size(), "3 条 OSD 消息应有 3 个不同的 bid");

        // 验证字段分组：Group 1 含 backup_battery，Group 2 含 wireless_link，Group 3 含 mode_code
        JsonNode group1 = objectMapper.readTree(jsonCaptor.getAllValues().get(0)).path("data");
        JsonNode group2 = objectMapper.readTree(jsonCaptor.getAllValues().get(1)).path("data");
        JsonNode group3 = objectMapper.readTree(jsonCaptor.getAllValues().get(2)).path("data");

        assertTrue(group1.has("backup_battery"), "Group 1 应包含 backup_battery");
        assertTrue(group1.has("maintain_status"), "Group 1 应包含 maintain_status");
        assertFalse(group1.has("electric_supply_voltage"), "Group 1 不应包含 electric_supply_voltage（仅 Dock1 上报，Dock3 未定义）");
        assertFalse(group1.has("mode_code"), "Group 1 不应包含 mode_code");

        assertTrue(group2.has("wireless_link"), "Group 2 应包含 wireless_link");
        assertTrue(group2.has("drc_state"), "Group 2 应包含 drc_state");
        assertFalse(group2.has("mode_code"), "Group 2 不应包含 mode_code");

        assertTrue(group3.has("mode_code"), "Group 3 应包含 mode_code");
        assertTrue(group3.has("latitude"), "Group 3 应包含 latitude");
        assertTrue(group3.has("cover_state"), "Group 3 应包含 cover_state");
        assertFalse(group3.has("putter_state"), "Group 3 不应包含 putter_state（仅 Dock1 上报，Dock3 未定义）");
        assertFalse(group3.has("backup_battery"), "Group 3 不应包含 backup_battery");
    }

    // ==================== DJI 协议对齐测试（2026-08-12 修复验证） ====================

    /**
     * 辅助：捕获 Dock OSD JSON（合并分多条推送的所有 dock OSD data）。
     * <p>对齐 DJI 文档「机场的设备属性推送是分多条推送的」，dock OSD 分 3 条消息推送，
     * 本方法合并 3 条消息的 data 为一条 JSON 供字段完整性验证。</p>
     */
    private String captureDockOsdJson(ObjectMapper objectMapper, DeviceState state) {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, testRuntimeConfig(), new DockTopicSchema()));
        simulator.publishOsd();

        String dockSn = testRuntimeConfig().getDockSn();
        String dockOsdTopic = "thing/product/" + dockSn + "/osd";

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.atLeast(1)).publish(topicCaptor.capture(), jsonCaptor.capture());

        // 合并所有 dock OSD 消息的 data
        Map<String, Object> mergedData = new LinkedHashMap<>();
        long timestamp = 0;
        for (int i = 0; i < topicCaptor.getAllValues().size(); i++) {
            if (dockOsdTopic.equals(topicCaptor.getAllValues().get(i))) {
                try {
                    JsonNode node = objectMapper.readTree(jsonCaptor.getAllValues().get(i));
                    JsonNode data = node.path("data");
                    if (data.isObject()) {
                        data.fields().forEachRemaining(e -> mergedData.put(e.getKey(), e.getValue()));
                    }
                    if (timestamp == 0) {
                        timestamp = node.path("timestamp").asLong();
                    }
                } catch (Exception e) {
                    // ignore parse errors
                }
            }
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", "merged");
        envelope.put("tid", "merged");
        envelope.put("timestamp", timestamp);
        envelope.put("gateway", dockSn);
        envelope.put("data", mergedData);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 辅助：捕获 Drone OSD JSON。
     */
    private String captureDroneOsdJson(ObjectMapper objectMapper, DeviceState state) {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                testRuntimeConfig(),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, testRuntimeConfig(), new DockTopicSchema()));
        simulator.publishOsd();

        String droneSn = testRuntimeConfig().getDroneSn();
        String droneOsdTopic = "thing/product/" + droneSn + "/osd";

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.atLeast(1)).publish(topicCaptor.capture(), jsonCaptor.capture());

        for (int i = 0; i < topicCaptor.getAllValues().size(); i++) {
            if (droneOsdTopic.equals(topicCaptor.getAllValues().get(i))) {
                return jsonCaptor.getAllValues().get(i);
            }
        }
        throw new AssertionError("未找到 drone OSD 消息");
    }

    /**
     * 辅助：捕获 Pilot 遥控器 OSD JSON。
     * <p>默认使用 RC Plus 2（对齐 DJI RC Plus 2 文档），可通过 controllerType 参数切换型号。</p>
     */
    private String capturePilotOsdJson(ObjectMapper objectMapper, DeviceState state) {
        return capturePilotOsdJson(objectMapper, state, RcModel.RC_PLUS_2);
    }

    /**
     * 辅助：捕获 Pilot 遥控器 OSD JSON，指定遥控器型号。
     */
    private String capturePilotOsdJson(ObjectMapper objectMapper, DeviceState state, RcModel controllerType) {
        RuntimeConfig pilotConfig = testRuntimeConfig();
        pilotConfig.setDeviceMode(DeviceMode.PILOT);
        pilotConfig.setControllerType(controllerType);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceSimulator simulator = new DeviceSimulator(
                testProps(), mqtt, state, objectMapper,
                pilotConfig,
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, pilotConfig, new DockTopicSchema()));
        simulator.publishOsd();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.atLeast(1)).publish(Mockito.anyString(), captor.capture());
        return captor.getAllValues().get(0);
    }

    // ===== #8: live_capacity 三层嵌套 =====

    @DisplayName("补充测试：live_capacity 不在 OSD 中（pushMode=1 应在 state topic）")
    @Test
    void dockOsdLiveCapacityThreeLayerNesting() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = captureDockOsdJson(objectMapper, state);
        JsonNode data = objectMapper.readTree(json).path("data");

        // live_capacity 的 pushMode=1（DJI Dock1 properties 文档），应在 state topic 上报，不应出现在 OSD 中
        assertFalse(data.has("live_capacity"), "live_capacity pushMode=1，不应在 OSD 中（应在 state topic）");
        // silent_mode 同理 pushMode=1
        assertFalse(data.has("silent_mode"), "silent_mode pushMode=1，不应在 OSD 中（应在 state topic）");

        // position_state 应包含 is_calibration 字段（DJI Dock1 properties 文档）
        JsonNode positionState = data.path("position_state");
        // 注意：Dock OSD 中 position_state 可选，若存在则应包含 is_calibration
        if (!positionState.isMissingNode() && positionState.isObject()) {
            assertTrue(positionState.has("is_calibration"), "position_state 应包含 is_calibration 字段");
        }

        // backup_battery 字段顺序：switch / voltage / temperature（DJI 文档定义）
        JsonNode backupBattery = data.path("backup_battery");
        if (backupBattery.isObject()) {
            assertTrue(backupBattery.has("switch"), "backup_battery 应包含 switch 字段");
            assertTrue(backupBattery.has("voltage"), "backup_battery 应包含 voltage 字段");
            assertTrue(backupBattery.has("temperature"), "backup_battery 应包含 temperature 字段");
        }
    }

    // ===== #9: drone_charge_state 是 struct（非 int） =====

    @DisplayName("补充测试：drone_charge_state 为 struct 类型")
    @Test
    void dockOsdDroneChargeStateIsStruct() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = captureDockOsdJson(objectMapper, state);
        JsonNode data = objectMapper.readTree(json).path("data");

        JsonNode chargeState = data.path("drone_charge_state");
        assertTrue(chargeState.isObject(), "drone_charge_state 应为 struct");
        assertTrue(chargeState.has("capacity_percent"));
        assertTrue(chargeState.has("state"));
    }

    // ===== #10: rainfall 是 int（非 double） =====

    @DisplayName("补充测试：rainfall 为 int 类型")
    @Test
    void dockOsdRainfallIsInt() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = captureDockOsdJson(objectMapper, state);
        JsonNode data = objectMapper.readTree(json).path("data");

        JsonNode rainfall = data.path("rainfall");
        assertTrue(rainfall.isInt(), "rainfall 应为 int 类型");
        int val = rainfall.asInt();
        assertTrue(val >= 0 && val <= 3, "rainfall 取值范围 0-3");
    }

    // ===== #23: OSD 信封无 version 字段 =====

    @DisplayName("补充测试：OSD 信封无 version 字段")
    @Test
    void dockOsdEnvelopeHasNoVersion() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = captureDockOsdJson(objectMapper, state);
        JsonNode node = objectMapper.readTree(json);

        assertFalse(node.has("version"), "OSD 信封不应包含 version 字段");
        assertTrue(node.has("tid"), "OSD 信封应包含 tid");
        assertTrue(node.has("gateway"), "OSD 信封应包含 gateway");
    }

    // ===== #11: height（相对起飞点）和 elevation（椭球高/海拔）值正确 =====

    @DisplayName("补充测试：height 与 elevation 值正确")
    @Test
    void droneOsdHeightElevationCorrect() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        state.setDroneHeight(120.0);   // 相对起飞点高度
        String json = captureDroneOsdJson(objectMapper, state);
        JsonNode data = objectMapper.readTree(json).path("data");

        // 字段对齐 DJI M30/M3D/M4D properties 文档：
        // height = 相对起飞点高度（droneHeight）
        // elevation = 椭球高/海拔（droneElevation，默认 500.0）
        double height = data.path("height").asDouble();
        double elevation = data.path("elevation").asDouble();
        assertEquals(120.0, height, 0.01, "height 应为相对起飞点高度");
        assertTrue(elevation > height, "elevation（椭球高/海拔）应大于 height（相对起飞点高度）");
    }

    // ===== #15/#16: rtk_number 字段名，无 gps_number_in_rtcm =====

    @DisplayName("补充测试：position_state 包含 rtk_number 非 rtcm_number")
    @Test
    void droneOsdHasRtkNumberNotRtcmNumber() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = captureDroneOsdJson(objectMapper, state);
        JsonNode data = objectMapper.readTree(json).path("data");

        // rtk_number 在 position_state 内部
        JsonNode posState = data.path("position_state");
        assertTrue(posState.has("rtk_number"), "应为 rtk_number（非 rtcm_number）");
        assertFalse(posState.has("rtcm_number"), "不应有 rtcm_number");
        assertFalse(data.has("gps_number_in_rtcm"), "不应有 gps_number_in_rtcm");
    }

    // ===== #17/#18/#19/#20: Pilot 遥控器 OSD 字段对齐 DJI RC Plus 2 / RC Plus / RC Pro 文档 =====

    /**
     * RC Plus 2 OSD 字段对齐 DJI 文档（pushMode=0 的 OSD 字段）。
     * <p>参考：https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/dji-rc-plus-2/properties.html
     */
    @DisplayName("补充测试：RC Plus 2 OSD 字段对齐 DJI 文档")
    @Test
    void pilotOsdFieldsMatchDjiSpec() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = capturePilotOsdJson(objectMapper, state);
        JsonNode data = objectMapper.readTree(json).path("data");

        // RC Plus 2 文档定义的 pushMode=0 OSD 字段
        assertTrue(data.has("capacity_percent"), "RC Plus 2 OSD 应包含 capacity_percent");
        assertTrue(data.has("latitude"), "RC Plus 2 OSD 应包含 latitude");
        assertTrue(data.has("longitude"), "RC Plus 2 OSD 应包含 longitude");
        assertTrue(data.has("height"), "RC Plus 2 OSD 应包含 height");
        assertTrue(data.has("wireless_link"), "RC Plus 2 OSD 应包含 wireless_link");
        assertTrue(data.has("drc_state"), "RC Plus 2 OSD 应包含 drc_state");

        // RC Plus 2 文档无 country 字段（仅 RC Pro 有）
        assertFalse(data.has("country"), "RC Plus 2 OSD 不应包含 country（仅 RC Pro 有）");

        // drc_state 枚举值：0=未连接, 1=连接中, 2=已连接
        int drcState = data.path("drc_state").asInt();
        assertTrue(drcState >= 0 && drcState <= 2, "drc_state 取值 0/1/2");

        // wireless_link 子字段（RC Plus 2 文档定义的 10 个子字段）
        JsonNode wirelessLink = data.path("wireless_link");
        assertTrue(wirelessLink.has("dongle_number"), "wireless_link 应包含 dongle_number");
        assertTrue(wirelessLink.has("4g_link_state"), "wireless_link 应包含 4g_link_state");
        assertTrue(wirelessLink.has("sdr_link_state"), "wireless_link 应包含 sdr_link_state");
        assertTrue(wirelessLink.has("link_workmode"), "wireless_link 应包含 link_workmode");
        assertTrue(wirelessLink.has("sdr_quality"), "wireless_link 应包含 sdr_quality");
        assertTrue(wirelessLink.has("4g_quality"), "wireless_link 应包含 4g_quality");
        assertTrue(wirelessLink.has("4g_uav_quality"), "wireless_link 应包含 4g_uav_quality");
        assertTrue(wirelessLink.has("4g_gnd_quality"), "wireless_link 应包含 4g_gnd_quality");
        assertTrue(wirelessLink.has("sdr_freq_band"), "wireless_link 应包含 sdr_freq_band");
        assertTrue(wirelessLink.has("4g_freq_band"), "wireless_link 应包含 4g_freq_band");

        // 4g_link_state / sdr_link_state 枚举值：0=断开, 1=连接
        int link4g = wirelessLink.path("4g_link_state").asInt();
        assertTrue(link4g == 0 || link4g == 1, "4g_link_state 取值 0 或 1");
        int sdrLink = wirelessLink.path("sdr_link_state").asInt();
        assertTrue(sdrLink == 0 || sdrLink == 1, "sdr_link_state 取值 0 或 1");

        // link_workmode 枚举值：0=SDR 模式, 1=4G 融合模式
        int workmode = wirelessLink.path("link_workmode").asInt();
        assertTrue(workmode == 0 || workmode == 1, "link_workmode 取值 0 或 1");

        // 4g_quality / sdr_quality / 4g_uav_quality / 4g_gnd_quality 值在 0-5 范围
        assertTrue(wirelessLink.path("4g_quality").asInt() >= 0 && wirelessLink.path("4g_quality").asInt() <= 5, "4g_quality 取值 0-5");
        assertTrue(wirelessLink.path("sdr_quality").asInt() >= 0 && wirelessLink.path("sdr_quality").asInt() <= 5, "sdr_quality 取值 0-5");
        assertTrue(wirelessLink.path("4g_uav_quality").asInt() >= 0 && wirelessLink.path("4g_uav_quality").asInt() <= 5, "4g_uav_quality 取值 0-5");
        assertTrue(wirelessLink.path("4g_gnd_quality").asInt() >= 0 && wirelessLink.path("4g_gnd_quality").asInt() <= 5, "4g_gnd_quality 取值 0-5");

        // OSD 信封
        JsonNode node = objectMapper.readTree(json);
        assertTrue(node.has("tid"), "Pilot OSD 信封应包含 tid");
        assertTrue(node.has("gateway"), "Pilot OSD 信封应包含 gateway");
        assertFalse(node.has("version"), "Pilot OSD 信封不应包含 version");
    }

    /**
     * RC Plus OSD 字段差异化验证：无 drc_state、无 country。
     * <p>参考：https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/rc-plus/properties.html
     */
    @DisplayName("补充测试：RC Plus OSD 无 drc_state 和 country")
    @Test
    void pilotOsdRcPlusNoDrcStateNoCountry() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = capturePilotOsdJson(objectMapper, state, RcModel.RC_PLUS);
        JsonNode data = objectMapper.readTree(json).path("data");

        // RC Plus 文档无 drc_state 字段
        assertFalse(data.has("drc_state"), "RC Plus OSD 不应包含 drc_state（仅 RC Plus 2/RC Pro 有）");
        // RC Plus 文档无 country 字段
        assertFalse(data.has("country"), "RC Plus OSD 不应包含 country（仅 RC Pro 有）");
        // RC Plus 共有字段
        assertTrue(data.has("capacity_percent"), "RC Plus OSD 应包含 capacity_percent");
        assertTrue(data.has("wireless_link"), "RC Plus OSD 应包含 wireless_link");
    }

    /**
     * RC Pro OSD 字段差异化验证：有 drc_state、有 country。
     * <p>参考：https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/rc-pro/properties.html
     */
    @DisplayName("补充测试：RC Pro OSD 包含 country")
    @Test
    void pilotOsdRcProHasCountry() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = capturePilotOsdJson(objectMapper, state, RcModel.RC_PRO);
        JsonNode data = objectMapper.readTree(json).path("data");

        // RC Pro 文档有 country 字段
        assertTrue(data.has("country"), "RC Pro OSD 应包含 country");
        // RC Pro 文档有 drc_state 字段
        assertTrue(data.has("drc_state"), "RC Pro OSD 应包含 drc_state");
    }

    // ===== #1/#2: update_topo 字段完整性（device_secret/nonce/thing_version/domain） =====

    @SuppressWarnings("unchecked")
    @DisplayName("TC-ONLINE-001：注册成功后自动上线（update_topo 字段结构）")
    @Test
    void updateTopoDataContainsRequiredFields() throws Exception {
        RuntimeConfig config = testRuntimeConfig();
        DeviceState state = new DeviceState();
        state.setDroneActivated(true);
        DockOnlineService service = new DockOnlineService(
                testProps(), Mockito.mock(MqttClientManager.class), state,
                new ObjectMapper(), config, Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        Method method = DockOnlineService.class.getDeclaredMethod("buildUpdateTopoData");
        method.setAccessible(true);
        Map<String, Object> data = (Map<String, Object>) method.invoke(service);

        // 网关顶层字段
        assertEquals(String.valueOf(config.getDockType().domain()), data.get("domain"));
        assertEquals(config.getDockType().type(), data.get("type"));
        assertEquals(config.getDockType().subType(), data.get("sub_type"));
        assertNotNull(data.get("device_secret"));
        assertNotNull(data.get("nonce"));
        assertEquals(config.getThingVersion(), data.get("thing_version"));

        // sub_devices 元素字段
        List<Map<String, Object>> subDevices = (List<Map<String, Object>>) data.get("sub_devices");
        assertEquals(1, subDevices.size());
        Map<String, Object> sub = subDevices.get(0);
        assertEquals(config.getDroneSn(), sub.get("sn"));
        assertEquals(String.valueOf(config.getDroneType().domain()), sub.get("domain"));
        assertEquals(config.getDroneType().type(), sub.get("type"));
        assertEquals(config.getDroneType().subType(), sub.get("sub_type"));
        assertNotNull(sub.get("device_secret"));
        assertNotNull(sub.get("nonce"));
        assertEquals(config.getThingVersion(), sub.get("thing_version"));
        assertFalse(sub.containsKey("firmware_version"), "子设备不应有 firmware_version（应为 thing_version）");
    }

    // ===== #3/#4: sleep topo type 动态值（非硬编码 3） =====

    @DisplayName("TC-ONLINE-008：飞行器休眠时发送 update_topo 通知平台")
    @Test
    void sleepTopoTypeMatchesDockType() throws Exception {
        RuntimeConfig config = testRuntimeConfig();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService service = new DockOnlineService(
                testProps(), mqtt, state,
                new ObjectMapper(), config, Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        service.publishDroneSleepTopo();

        // 捕获 publishJson 的 Object 参数（mock 不执行内部 publish 调用）
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.atLeast(1)).publishJson(Mockito.anyString(), captor.capture());
        String json = new ObjectMapper().writeValueAsString(captor.getAllValues().get(0));

        JsonNode node = new ObjectMapper().readTree(json);
        JsonNode data = node.path("data");

        // type 应为 dockType.type()（Dock3=3），非硬编码
        assertEquals(config.getDockType().type(), data.path("type").asInt());
        // domain 字段存在
        assertEquals(String.valueOf(config.getDockType().domain()), data.path("domain").asText());
        // sub_devices 为空数组
        assertTrue(data.path("sub_devices").isArray());
        assertEquals(0, data.path("sub_devices").size());
    }

    // ===== TC-BUILDER-011: pushMode=0 简单字段完整覆盖（OSD 定频上报） =====

    @DisplayName("补充测试：Dock OSD pushMode=0 简单字段完整")
    @Test
    void dockOsdPushMode0SimpleFieldsComplete() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = captureDockOsdJson(objectMapper, state);
        JsonNode data = objectMapper.readTree(json).path("data");

        // pushMode=0 简单字段（DJI Dock3 properties 文档标注为定频上报的标量属性）
        assertTrue(data.has("drc_state"), "OSD 应包含 drc_state（pushMode=0）");
        assertTrue(data.has("environment_temperature"), "OSD 应包含 environment_temperature（pushMode=0）");
        assertTrue(data.has("working_current"), "OSD 应包含 working_current（pushMode=0）");
        assertTrue(data.has("working_voltage"), "OSD 应包含 working_voltage（pushMode=0）");
        assertTrue(data.has("first_power_on"), "OSD 应包含 first_power_on（pushMode=0）");
        assertTrue(data.has("activation_time"), "OSD 应包含 activation_time（pushMode=0）");
        assertTrue(data.has("emergency_stop_state"), "OSD 应包含 emergency_stop_state（pushMode=0）");
        assertTrue(data.has("alarm_state"), "OSD 应包含 alarm_state（pushMode=0）");
        assertTrue(data.has("battery_store_mode"), "OSD 应包含 battery_store_mode（pushMode=0）");
        assertTrue(data.has("job_number"), "OSD 应包含 job_number（pushMode=0）");
        assertTrue(data.has("flighttask_step_code"), "OSD 应包含 flighttask_step_code（pushMode=0）");
    }

    // ===== TC-BUILDER-012: pushMode=0 结构体字段完整覆盖（OSD 定频上报） =====

    @DisplayName("补充测试：Dock OSD pushMode=0 结构体字段完整")
    @Test
    void dockOsdPushMode0StructFieldsComplete() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        state.setDroneActivated(true);
        String json = captureDockOsdJson(objectMapper, state);
        JsonNode data = objectMapper.readTree(json).path("data");

        // media_file_detail
        JsonNode mediaFileDetail = data.path("media_file_detail");
        assertTrue(mediaFileDetail.isObject(), "media_file_detail 应为 struct");
        assertTrue(mediaFileDetail.has("remain_upload"), "media_file_detail 应包含 remain_upload");

        // position_state
        JsonNode positionState = data.path("position_state");
        assertTrue(positionState.isObject(), "position_state 应为 struct");
        assertTrue(positionState.has("is_calibration"), "position_state 应包含 is_calibration");
        assertTrue(positionState.has("is_fixed"), "position_state 应包含 is_fixed");
        assertTrue(positionState.has("quality"), "position_state 应包含 quality");
        assertTrue(positionState.has("gps_number"), "position_state 应包含 gps_number");
        assertTrue(positionState.has("rtk_number"), "position_state 应包含 rtk_number");

        // wireless_link
        JsonNode wirelessLink = data.path("wireless_link");
        assertTrue(wirelessLink.isObject(), "wireless_link 应为 struct");
        assertTrue(wirelessLink.has("dongle_number"), "wireless_link 应包含 dongle_number");
        assertTrue(wirelessLink.has("4g_link_state"), "wireless_link 应包含 4g_link_state");
        assertTrue(wirelessLink.has("sdr_link_state"), "wireless_link 应包含 sdr_link_state");
        assertTrue(wirelessLink.has("link_workmode"), "wireless_link 应包含 link_workmode");
        assertTrue(wirelessLink.has("sdr_quality"), "wireless_link 应包含 sdr_quality");
        assertTrue(wirelessLink.has("4g_quality"), "wireless_link 应包含 4g_quality");
        assertTrue(wirelessLink.has("4g_uav_quality"), "wireless_link 应包含 4g_uav_quality");
        assertTrue(wirelessLink.has("4g_gnd_quality"), "wireless_link 应包含 4g_gnd_quality");
        assertTrue(wirelessLink.has("sdr_freq_band"), "wireless_link 应包含 sdr_freq_band");
        assertTrue(wirelessLink.has("4g_freq_band"), "wireless_link 应包含 4g_freq_band");

        // alternate_land_point
        JsonNode alternateLandPoint = data.path("alternate_land_point");
        assertTrue(alternateLandPoint.isObject(), "alternate_land_point 应为 struct");
        assertTrue(alternateLandPoint.has("longitude"), "alternate_land_point 应包含 longitude");
        assertTrue(alternateLandPoint.has("latitude"), "alternate_land_point 应包含 latitude");
        assertTrue(alternateLandPoint.has("safe_land_height"), "alternate_land_point 应包含 safe_land_height");
        assertTrue(alternateLandPoint.has("is_configured"), "alternate_land_point 应包含 is_configured");
        assertTrue(alternateLandPoint.has("height"), "alternate_land_point 应包含 height");

        // self_converge_coordinate
        JsonNode selfConverge = data.path("self_converge_coordinate");
        assertTrue(selfConverge.isObject(), "self_converge_coordinate 应为 struct");
        assertTrue(selfConverge.has("latitude"), "self_converge_coordinate 应包含 latitude");
        assertTrue(selfConverge.has("longitude"), "self_converge_coordinate 应包含 longitude");
        assertTrue(selfConverge.has("height"), "self_converge_coordinate 应包含 height");

        // drone_battery_maintenance_info
        JsonNode batteryMaintenance = data.path("drone_battery_maintenance_info");
        assertTrue(batteryMaintenance.isObject(), "drone_battery_maintenance_info 应为 struct");
        assertTrue(batteryMaintenance.has("maintenance_state"), "drone_battery_maintenance_info 应包含 maintenance_state");
        assertTrue(batteryMaintenance.has("maintenance_time_left"), "drone_battery_maintenance_info 应包含 maintenance_time_left");
        assertTrue(batteryMaintenance.has("heat_state"), "drone_battery_maintenance_info 应包含 heat_state");
        assertTrue(batteryMaintenance.has("batteries"), "drone_battery_maintenance_info 应包含 batteries");

        // maintain_status
        JsonNode maintainStatus = data.path("maintain_status");
        assertTrue(maintainStatus.isObject(), "maintain_status 应为 struct");
        assertTrue(maintainStatus.has("maintain_status_array"), "maintain_status 应包含 maintain_status_array");
        assertTrue(maintainStatus.path("maintain_status_array").isArray(), "maintain_status_array 应为数组");
    }

    // ===== TC-PROP-005: 机场上线推送 pushMode=1 简单属性到 state topic =====

    @DisplayName("TC-PROP-005：机场上线推送 pushMode=1 简单属性到 state topic")
    @Test
    void dockStatePushMode1SimpleFieldsComplete() throws Exception {
        RuntimeConfig config = testRuntimeConfig();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        DockOnlineService service = new DockOnlineService(
                testProps(), mqtt, state,
                new ObjectMapper(), config, Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        service.publishDockState();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.atLeast(1)).publishJson(Mockito.anyString(), captor.capture());
        String json = new ObjectMapper().writeValueAsString(captor.getAllValues().get(0));
        JsonNode data = new ObjectMapper().readTree(json).path("data");

        // pushMode=1 简单属性
        assertTrue(data.has("firmware_version"), "state 应包含 firmware_version（pushMode=1）");
        assertTrue(data.has("firmware_upgrade_status"), "state 应包含 firmware_upgrade_status（pushMode=1）");
        assertTrue(data.has("compatible_status"), "state 应包含 compatible_status（pushMode=1）");
        assertTrue(data.has("acc_time"), "state 应包含 acc_time（pushMode=1）");
        assertTrue(data.has("air_transfer_enable"), "state 应包含 air_transfer_enable（pushMode=1）");
        assertTrue(data.has("user_experience_improvement"), "state 应包含 user_experience_improvement（pushMode=1）");
        assertTrue(data.has("silent_mode"), "state 应包含 silent_mode（pushMode=1）");

        // envelope 字段
        JsonNode node = new ObjectMapper().readTree(json);
        assertTrue(node.has("bid"), "state 信封应包含 bid");
        assertTrue(node.has("tid"), "state 信封应包含 tid");
        assertTrue(node.has("timestamp"), "state 信封应包含 timestamp");
        assertTrue(node.has("gateway"), "state 信封应包含 gateway");
    }

    // ===== TC-PROP-006: 机场上线推送 pushMode=1 复杂结构属性到 state topic =====

    @DisplayName("TC-PROP-006：机场上线推送 pushMode=1 复杂结构属性到 state topic")
    @Test
    void dockStatePushMode1StructFieldsComplete() throws Exception {
        RuntimeConfig config = testRuntimeConfig();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        DockOnlineService service = new DockOnlineService(
                testProps(), mqtt, state,
                new ObjectMapper(), config, Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        service.publishDockState();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.atLeast(1)).publishJson(Mockito.anyString(), captor.capture());
        String json = new ObjectMapper().writeValueAsString(captor.getAllValues().get(0));
        JsonNode data = new ObjectMapper().readTree(json).path("data");

        // rtcm_info
        JsonNode rtcmInfo = data.path("rtcm_info");
        assertTrue(rtcmInfo.isObject(), "rtcm_info 应为 struct");
        assertTrue(rtcmInfo.has("mount_point"), "rtcm_info 应包含 mount_point");
        assertTrue(rtcmInfo.has("port"), "rtcm_info 应包含 port");
        assertTrue(rtcmInfo.has("host"), "rtcm_info 应包含 host");
        assertTrue(rtcmInfo.has("rtcm_device_type"), "rtcm_info 应包含 rtcm_device_type");
        assertTrue(rtcmInfo.has("source_type"), "rtcm_info 应包含 source_type");

        // wireless_link_topo
        JsonNode wirelessLinkTopo = data.path("wireless_link_topo");
        assertTrue(wirelessLinkTopo.isObject(), "wireless_link_topo 应为 struct");
        assertTrue(wirelessLinkTopo.has("secret_code"), "wireless_link_topo 应包含 secret_code");
        assertTrue(wirelessLinkTopo.path("secret_code").isArray(), "secret_code 应为数组");
        assertEquals(28, wirelessLinkTopo.path("secret_code").size(), "secret_code 长度应为 28");
        assertTrue(wirelessLinkTopo.has("center_node"), "wireless_link_topo 应包含 center_node");
        JsonNode centerNode = wirelessLinkTopo.path("center_node");
        assertTrue(centerNode.has("sdr_id"), "center_node 应包含 sdr_id");
        assertTrue(centerNode.has("sn"), "center_node 应包含 sn");
        assertEquals(config.getDroneSn(), centerNode.path("sn").asText(), "center_node.sn 应等于飞行器 SN");
        assertTrue(wirelessLinkTopo.has("leaf_nodes"), "wireless_link_topo 应包含 leaf_nodes");

        // dongle_infos
        JsonNode dongleInfos = data.path("dongle_infos");
        assertTrue(dongleInfos.isArray(), "dongle_infos 应为数组");
        assertEquals(1, dongleInfos.size(), "dongle_infos 应有 1 个元素");
        JsonNode dongleInfo = dongleInfos.get(0);
        assertTrue(dongleInfo.has("imei"), "dongle_info 应包含 imei");
        assertTrue(dongleInfo.has("dongle_type"), "dongle_info 应包含 dongle_type");
        assertTrue(dongleInfo.has("eid"), "dongle_info 应包含 eid");
        assertTrue(dongleInfo.has("esim_activate_state"), "dongle_info 应包含 esim_activate_state");
        assertTrue(dongleInfo.has("sim_card_state"), "dongle_info 应包含 sim_card_state");
        assertTrue(dongleInfo.has("sim_slot"), "dongle_info 应包含 sim_slot");
        assertTrue(dongleInfo.has("esim_infos"), "dongle_info 应包含 esim_infos");
        assertTrue(dongleInfo.has("sim_info"), "dongle_info 应包含 sim_info");
        JsonNode simInfo = dongleInfo.path("sim_info");
        assertTrue(simInfo.has("telecom_operator"), "sim_info 应包含 telecom_operator");
        assertTrue(simInfo.has("sim_type"), "sim_info 应包含 sim_type");
        assertTrue(simInfo.has("iccid"), "sim_info 应包含 iccid");

        // live_status
        JsonNode liveStatus = data.path("live_status");
        assertTrue(liveStatus.isArray(), "live_status 应为数组");
        assertEquals(0, liveStatus.size(), "无在推视频流时 live_status 应为空数组");
    }

    // ===== 补充诊断：完整上线流程向 state topic 推送次数 =====

    /**
     * 验证 online() 完整流程（含模拟平台回复）向 thing/product/{dockSn}/state 精确推送 1 条消息。
     * <p>背景：用户在 MQTT 日志面板观察到上线时 state topic 出现两条 [发] 记录。
     * 本测试用 mock 平台同步回复 requests_reply/status_reply，走完
     * config → airport_bind_status → airport_organization_get → airport_organization_bind → update_topo
     * 全流程，统计 state topic 的实际推送次数。</p>
     */
    @DisplayName("补充测试：上线流程向 state topic 精确推送一条机场属性消息")
    @Test
    void onlinePushesExactlyOneDockStateMessage() throws Exception {
        RuntimeConfig config = testRuntimeConfig();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);

        // 捕获 reply topic 监听器（模拟平台回复入口）
        Map<String, MqttClientManager.MqttMessageListener> listeners = new LinkedHashMap<>();
        Mockito.doAnswer(inv -> {
            listeners.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(mqtt).addListener(Mockito.anyString(), Mockito.any());

        ObjectMapper om = new ObjectMapper();
        List<String> stateTopics = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Mockito.doAnswer(inv -> {
            String topic = inv.getArgument(0);
            String payload = om.writeValueAsString(inv.getArgument(1));
            JsonNode node = om.readTree(payload);
            String tid = node.path("tid").asText();
            String bid = node.path("bid").asText();
            String method = node.path("method").asText();
            if (topic.endsWith("/requests")) {
                // 模拟平台同步回复 requests_reply
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("result", 0);
                if ("config".equals(method)) {
                    data.put("app_id", "test-app");
                    data.put("app_license", "");
                }
                Map<String, Object> reply = new LinkedHashMap<>();
                reply.put("tid", tid);
                reply.put("bid", bid);
                reply.put("timestamp", System.currentTimeMillis());
                reply.put("method", method + "_reply");
                reply.put("data", data);
                String replyTopic = topic + "_reply";
                MqttClientManager.MqttMessageListener listener = listeners.get(replyTopic);
                if (listener != null) {
                    listener.onMessage(replyTopic, om.writeValueAsString(reply));
                }
            } else if (topic.endsWith("/status")) {
                // 模拟平台同步回复 status_reply（update_topo）
                Map<String, Object> reply = new LinkedHashMap<>();
                reply.put("tid", tid);
                reply.put("bid", bid);
                reply.put("timestamp", System.currentTimeMillis());
                reply.put("method", method);
                reply.put("data", Map.of("result", 0));
                String replyTopic = topic + "_reply";
                MqttClientManager.MqttMessageListener listener = listeners.get(replyTopic);
                if (listener != null) {
                    listener.onMessage(replyTopic, om.writeValueAsString(reply));
                }
            } else if (topic.endsWith("/state")) {
                stateTopics.add(topic);
            }
            return null;
        }).when(mqtt).publishJson(Mockito.anyString(), Mockito.any());

        DockOnlineService service = new DockOnlineService(
                testProps(), mqtt, state, om, config,
                Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        DockOnlineService.OnlineResult result = service.online();

        assertTrue(result.success(), "模拟平台全成功回复下上线应成功");
        assertEquals(1, stateTopics.size(),
                "上线流程应向 state topic 精确推送 1 条机场属性消息，实际推送 " + stateTopics.size() + " 条: " + stateTopics);
    }

    // ===== TC-BUILDER-002: M3D/M4D drone state 包含 wireless_link_topo（pushMode=1） =====

    @DisplayName("补充测试：M4D drone state 包含 wireless_link_topo")
    @Test
    void droneStateM4dWirelessLinkTopoInState() throws Exception {
        RuntimeConfig config = testRuntimeConfig();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        DockOnlineService service = new DockOnlineService(
                testProps(), mqtt, state,
                new ObjectMapper(), config, Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());

        service.publishDroneState();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt, Mockito.atLeast(1)).publishJson(Mockito.anyString(), captor.capture());
        String json = new ObjectMapper().writeValueAsString(captor.getAllValues().get(0));
        JsonNode data = new ObjectMapper().readTree(json).path("data");

        // M4TD（默认设备类型）state 应包含 wireless_link_topo（pushMode=1，M3D/M3TD/M4D/M4TD 均上报）
        assertTrue(data.has("wireless_link_topo"), "M3D/M3TD/M4D/M4TD drone state 应包含 wireless_link_topo（pushMode=1）");
        JsonNode topo = data.path("wireless_link_topo");
        assertTrue(topo.has("secret_code"), "wireless_link_topo 应包含 secret_code");
        assertTrue(topo.path("secret_code").isArray(), "secret_code 应为数组");
        assertEquals(28, topo.path("secret_code").size(), "secret_code 长度应为 28");
        assertTrue(topo.has("center_node"), "wireless_link_topo 应包含 center_node");
        assertTrue(topo.has("leaf_nodes"), "wireless_link_topo 应包含 leaf_nodes");
    }

    // ===== TC-REG-018: airport_bind_status 返回非0 result 停止注册 =====

    @DisplayName("补充测试：airport_bind_status 非0 result 停止注册")
    @Test
    void checkBindStatusResultNonZeroFailsAndTransfersCode() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree("{\"data\":{\"result\":210230}}");
        DockOnlineService.OnlineResult result = service.checkBindStatusResult(reply);
        assertFalse(result.success(), "result≠0 应判定为失败");
        assertEquals("210230", result.code(), "应透传 result 码");
    }

    @DisplayName("补充测试：airport_bind_status result=0 成功")
    @Test
    void checkBindStatusResultZeroSucceeds() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree(
                "{\"data\":{\"result\":0,\"output\":{\"bind_status\":["
                + "{\"sn\":\"dock-sn\",\"is_device_bind_organization\":true}"
                + "]}}}");
        DockOnlineService.OnlineResult result = service.checkBindStatusResult(reply);
        assertTrue(result.success(), "result=0 应判定为成功");
        assertEquals("0", result.code());
    }

    // ===== TC-REG-016: airport_organization_get/bind 返回非0 result 停止注册 =====

    @DisplayName("补充测试：airport_organization_get 非0 result 停止注册")
    @Test
    void checkOrgGetResultNonZeroFailsAndTransfersCode() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree("{\"data\":{\"result\":210229}}");
        DockOnlineService.OnlineResult result = service.checkOrgGetResult(reply);
        assertFalse(result.success(), "result=210229 应判定为失败");
        assertEquals("210229", result.code(), "应透传 result 码");
    }

    @DisplayName("补充测试：airport_organization_get result=0 成功")
    @Test
    void checkOrgGetResultZeroSucceeds() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree(
                "{\"data\":{\"result\":0,\"output\":{\"organization_name\":\"test-org\"}}}");
        DockOnlineService.OnlineResult result = service.checkOrgGetResult(reply);
        assertTrue(result.success(), "result=0 应判定为成功");
        assertEquals("0", result.code());
    }

    @DisplayName("补充测试：airport_organization_bind 非0 result 停止注册")
    @Test
    void checkOrgBindResultNonZeroFailsAndTransfersCode() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree("{\"data\":{\"result\":210230}}");
        DockOnlineService.OnlineResult result = service.checkOrgBindResult(reply);
        assertFalse(result.success(), "result≠0 应判定为失败");
        assertEquals("210230", result.code(), "应透传 result 码");
    }

    // ===== TC-REG-017: airport_organization_bind 设备级绑定结果判断 =====

    @DisplayName("airport_organization_bind err_infos 含非0 err_code 判定失败")
    @Test
    void checkOrgBindResultZeroWithErrInfosFailsAndTransfersFirstErrCode() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree(
                "{\"data\":{\"result\":0,\"output\":{\"err_infos\":["
                + "{\"err_code\":210231,\"sn\":\"dock-sn\"},"
                + "{\"err_code\":210232,\"sn\":\"drone-sn\"}"
                + "]}}}");
        DockOnlineService.OnlineResult result = service.checkOrgBindResult(reply);
        assertFalse(result.success(), "err_infos 含非0 err_code 应判定为失败");
        assertEquals("210231", result.code(), "应透传第一个非0 err_code");
    }

    @DisplayName("airport_organization_bind err_infos 全为0 err_code 判定成功")
    @Test
    void checkOrgBindResultZeroWithAllZeroErrCodesSucceeds() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree(
                "{\"data\":{\"result\":0,\"output\":{\"err_infos\":["
                + "{\"err_code\":0,\"sn\":\"dock-sn\"},"
                + "{\"err_code\":0,\"sn\":\"drone-sn\"}"
                + "]}}}");
        DockOnlineService.OnlineResult result = service.checkOrgBindResult(reply);
        assertTrue(result.success(), "err_infos 全为0 err_code 应判定为成功");
        assertEquals("0", result.code());
    }

    @DisplayName("airport_organization_bind err_infos 混合 err_code 判定失败")
    @Test
    void checkOrgBindResultZeroWithMixedErrCodesFails() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree(
                "{\"data\":{\"result\":0,\"output\":{\"err_infos\":["
                + "{\"err_code\":0,\"sn\":\"dock-sn\"},"
                + "{\"err_code\":210231,\"sn\":\"drone-sn\"}"
                + "]}}}");
        DockOnlineService.OnlineResult result = service.checkOrgBindResult(reply);
        assertFalse(result.success(), "err_infos 含非0 err_code 应判定为失败");
        assertEquals("210231", result.code(), "应透传第一个非0 err_code");
    }

    @DisplayName("airport_organization_bind 无 err_infos 成功")
    @Test
    void checkOrgBindResultZeroWithoutErrInfosSucceeds() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree("{\"data\":{\"result\":0}}");
        DockOnlineService.OnlineResult result = service.checkOrgBindResult(reply);
        assertTrue(result.success(), "result=0 且无 err_infos 应判定为成功");
        assertEquals("0", result.code());
    }

    @DisplayName("airport_organization_bind 空 err_infos 成功")
    @Test
    void checkOrgBindResultZeroWithEmptyErrInfosSucceeds() throws Exception {
        DockOnlineService service = newOnlineService();
        JsonNode reply = new ObjectMapper().readTree(
                "{\"data\":{\"result\":0,\"output\":{\"err_infos\":[]}}}");
        DockOnlineService.OnlineResult result = service.checkOrgBindResult(reply);
        assertTrue(result.success(), "err_infos 为空数组应判定为成功");
        assertEquals("0", result.code());
    }

    /** 构造 DockOnlineService 实例（mock MQTT，用于测试回复解析方法） */
    private DockOnlineService newOnlineService() {
        return new DockOnlineService(
                testProps(), Mockito.mock(MqttClientManager.class), new DeviceState(),
                new ObjectMapper(), testRuntimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());
    }
}
