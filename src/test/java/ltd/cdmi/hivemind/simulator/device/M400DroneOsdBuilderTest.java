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

package ltd.cdmi.hivemind.simulator.device;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M400DroneOsdBuilder 单元测试（TC-BUILDER-002-A）。
 * <p>验证 M400 Pilot 模式飞行器 OSD 字段集：
 * 简化版 type_subtype_gimbalindex（无 measure_target_*、thermal_*）、无 cameras、
 * 无 mode_code/gear/distance_limit_status/rth_altitude。
 */
class M400DroneOsdBuilderTest {

    private OsdContext ctx(DeviceType droneType) {
        DeviceState state = Mockito.mock(DeviceState.class);
        Mockito.lenient().when(state.getDroneLatitude()).thenReturn(22.5);
        Mockito.lenient().when(state.getDroneLongitude()).thenReturn(113.9);

        RuntimeConfig config = Mockito.mock(RuntimeConfig.class);
        Mockito.when(config.getDroneType()).thenReturn(droneType);

        SimulatorProperties props = Mockito.mock(SimulatorProperties.class);
        OsdStrategy strategy = new Dock3OsdStrategy();  // snake_case identity（Pilot 模式风格）
        return new OsdContext(state, props, config, strategy);
    }

    private OsdContext ctx() {
        return ctx(DeviceType.M400);
    }

    @Test
    void supportsM400M4EM4T() {
        M400DroneOsdBuilder builder = new M400DroneOsdBuilder();
        assertTrue(builder.supports(DeviceType.M400));
        assertTrue(builder.supports(DeviceType.M4E));
        assertTrue(builder.supports(DeviceType.M4T));
        assertFalse(builder.supports(DeviceType.M4D));
        assertFalse(builder.supports(DeviceType.M4TD));
    }

    @Test
    @SuppressWarnings("unchecked")
    void osdContainsSimplifiedGimbalInfo() {
        M400DroneOsdBuilder builder = new M400DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx());

        // type_subtype_gimbalindex 存在且为简化版
        Map<String, Object> gimbal = (Map<String, Object>) osd.get("type_subtype_gimbalindex");
        assertNotNull(gimbal, "应有 type_subtype_gimbalindex");
        assertEquals(0.0, gimbal.get("gimbal_pitch"));
        assertEquals(0.0, gimbal.get("gimbal_roll"));
        assertEquals(0.0, gimbal.get("gimbal_yaw"));
        assertEquals("82-0-0", gimbal.get("payload_index"), "payload_index 应为 H30 相机索引（82-0-0）");
        assertEquals(2.0, gimbal.get("zoom_factor"));
        // 无 measure_target_* / thermal_*
        assertFalse(gimbal.containsKey("measure_target_longitude"));
        assertFalse(gimbal.containsKey("measure_target_error_state"));
        assertFalse(gimbal.containsKey("thermal_gain_mode"));
        assertFalse(gimbal.containsKey("thermal_current_palette_style"));
    }

    @Test
    void osdNoCameras() {
        M400DroneOsdBuilder builder = new M400DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx());
        assertFalse(osd.containsKey("cameras"), "M400 不上报 cameras 数组");
    }

    @Test
    void osdContainsModeCodeGearFirmwareVersion() {
        M400DroneOsdBuilder builder = new M400DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx());
        // M400 Pilot 属性列表第二部分确认 pushMode=0
        assertTrue(osd.containsKey("mode_code"), "M400 Pilot 属性列表第二部分确认 mode_code pushMode=0");
        assertTrue(osd.containsKey("gear"), "M400 Pilot 属性列表第二部分确认 gear pushMode=0");
        assertTrue(osd.containsKey("firmware_version"), "M400 Pilot 属性列表第二部分确认 firmware_version pushMode=0");
    }

    @Test
    void osdNoDistanceLimitFields() {
        M400DroneOsdBuilder builder = new M400DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx());
        assertFalse(osd.containsKey("distance_limit_status"), "M400 Pilot 属性列表未列 distance_limit_status");
        assertFalse(osd.containsKey("rth_altitude"), "M400 Pilot 属性列表未列 rth_altitude");
    }

    @Test
    void osdHasCommonFields() {
        M400DroneOsdBuilder builder = new M400DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx());
        // 共用字段（基类提供，M400 属性列表包含）
        assertEquals(22.5, osd.get("latitude"));
        assertEquals(113.9, osd.get("longitude"));
        assertNotNull(osd.get("battery"));
        assertNotNull(osd.get("position_state"));
        assertNotNull(osd.get("storage"));
        assertNotNull(osd.get("obstacle_avoidance"));
        assertNotNull(osd.get("maintain_status"));
        assertNotNull(osd.get("height_limit"));
        assertNotNull(osd.get("night_lights_state"));
        assertNotNull(osd.get("activation_time"));
        assertNotNull(osd.get("track_id"));
        assertNotNull(osd.get("total_flight_time"));
        assertNotNull(osd.get("total_flight_distance"));
        assertNotNull(osd.get("total_flight_sorties"));
        assertNotNull(osd.get("is_near_area_limit"));
        assertNotNull(osd.get("is_near_height_limit"));
        assertNotNull(osd.get("home_distance"));
        assertNotNull(osd.get("wind_speed"));
        assertNotNull(osd.get("wind_direction"));
        assertNotNull(osd.get("attitude_pitch"));
        assertNotNull(osd.get("attitude_roll"));
        assertNotNull(osd.get("attitude_head"));
        assertNotNull(osd.get("elevation"));
        assertNotNull(osd.get("height"));
        assertNotNull(osd.get("horizontal_speed"));
        assertNotNull(osd.get("vertical_speed"));
        // mode_code/gear/firmware_version（pushMode=0 基础飞行字段，第二部分确认）
        assertNotNull(osd.get("mode_code"));
        assertNotNull(osd.get("gear"));
        assertNotNull(osd.get("firmware_version"));
    }
}
