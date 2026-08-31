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

import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.osd.Mavic3DroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.OsdContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mavic3DroneOsdBuilder 单元测试（TC-BUILDER-015）。
 * <p>验证 Mavic 3 行业系列 Pilot 模式飞行器 OSD 字段集：
 * country + cameras（完整数组），无 distance_limit_status/rth_altitude，
 * 无 firmware_version（pushMode=1 在 state），无 type_subtype_gimbalindex。
 */
class Mavic3DroneOsdBuilderTest {

    private OsdContext ctx(DroneModel droneType) {
        DeviceState state = Mockito.mock(DeviceState.class);
        Mockito.lenient().when(state.getDroneLatitude()).thenReturn(22.5);
        Mockito.lenient().when(state.getDroneLongitude()).thenReturn(113.9);

        RuntimeConfig config = Mockito.mock(RuntimeConfig.class);
        Mockito.when(config.getDroneType()).thenReturn(droneType);

        SimulatorProperties props = Mockito.mock(SimulatorProperties.class);
        return new OsdContext(state, props, config);
    }

    @DisplayName("TC-BUILDER-015：Mavic 3 DroneOsdBuilder 选择与字段集（Pilot 模式）")
    @Test
    void supportsMavic3() {
        Mavic3DroneOsdBuilder builder = new Mavic3DroneOsdBuilder();
        assertTrue(builder.supports(DroneModel.MAVIC_3E));
        assertTrue(builder.supports(DroneModel.MAVIC_3T));
        assertFalse(builder.supports(DroneModel.M4D));
        assertFalse(builder.supports(DroneModel.M400));
    }

    @DisplayName("TC-BUILDER-015：Mavic 3 DroneOsdBuilder 选择与字段集（Pilot 模式）— country 字段")
    @Test
    void osdContainsCountry() {
        Mavic3DroneOsdBuilder builder = new Mavic3DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx(DroneModel.MAVIC_3E));
        assertTrue(osd.containsKey("country"), "Mavic 3 应包含 country（国家区域码）");
        assertEquals("CN", osd.get("country"));
    }

    @DisplayName("TC-BUILDER-015：Mavic 3 DroneOsdBuilder 选择与字段集（Pilot 模式）— cameras 数组")
    @Test
    @SuppressWarnings("unchecked")
    void osdContainsCameras() {
        Mavic3DroneOsdBuilder builder = new Mavic3DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx(DroneModel.MAVIC_3E));

        List<Map<String, Object>> cameras = (List<Map<String, Object>>) osd.get("cameras");
        assertNotNull(cameras, "应有 cameras 数组");
        assertEquals(1, cameras.size());

        Map<String, Object> cam = cameras.get(0);
        assertEquals("66-0-0", cam.get("payload_index"), "Mavic 3E Camera payload_index=66-0-0");
        assertNotNull(cam.get("camera_mode"));
        assertNotNull(cam.get("zoom_factor"));
        assertNotNull(cam.get("liveview_world_region"));
        assertNotNull(cam.get("wide_exposure_mode"));
        assertNotNull(cam.get("zoom_focus_mode"));
    }

    @DisplayName("TC-BUILDER-015：Mavic 3 DroneOsdBuilder 选择与字段集（Pilot 模式）— Mavic 3T 红外测温字段")
    @Test
    @SuppressWarnings("unchecked")
    void mavic3TCamerasHasThermalFields() {
        Mavic3DroneOsdBuilder builder = new Mavic3DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx(DroneModel.MAVIC_3T));

        List<Map<String, Object>> cameras = (List<Map<String, Object>>) osd.get("cameras");
        Map<String, Object> cam = cameras.get(0);
        assertEquals("67-0-0", cam.get("payload_index"), "Mavic 3T Camera payload_index=67-0-0");
        assertTrue(cam.containsKey("ir_zoom_factor"), "Mavic 3T（thermal）应有 ir_zoom_factor");
        assertTrue(cam.containsKey("ir_metering_mode"), "Mavic 3T（thermal）应有 ir_metering_mode");
        assertTrue(cam.containsKey("ir_metering_point"), "Mavic 3T（thermal）应有 ir_metering_point");
        assertTrue(cam.containsKey("ir_metering_area"), "Mavic 3T（thermal）应有 ir_metering_area");
    }

    @DisplayName("TC-BUILDER-015：Mavic 3 DroneOsdBuilder 选择与字段集（Pilot 模式）— Mavic 3E 无红外字段")
    @Test
    @SuppressWarnings("unchecked")
    void mavic3ECamerasNoThermalFields() {
        Mavic3DroneOsdBuilder builder = new Mavic3DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx(DroneModel.MAVIC_3E));

        List<Map<String, Object>> cameras = (List<Map<String, Object>>) osd.get("cameras");
        Map<String, Object> cam = cameras.get(0);
        assertFalse(cam.containsKey("ir_zoom_factor"), "Mavic 3E（非 thermal）不应有 ir_zoom_factor");
        assertFalse(cam.containsKey("ir_metering_mode"), "Mavic 3E（非 thermal）不应有 ir_metering_mode");
    }

    @DisplayName("TC-BUILDER-015：Mavic 3 DroneOsdBuilder 选择与字段集（Pilot 模式）— 无 distance_limit_status/rth_altitude")
    @Test
    void osdNoDistanceLimitFields() {
        Mavic3DroneOsdBuilder builder = new Mavic3DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx(DroneModel.MAVIC_3E));
        assertFalse(osd.containsKey("distance_limit_status"), "Mavic 3 属性列表未列 distance_limit_status");
        assertFalse(osd.containsKey("rth_altitude"), "Mavic 3 属性列表未列 rth_altitude");
    }

    @DisplayName("TC-BUILDER-015：Mavic 3 DroneOsdBuilder 选择与字段集（Pilot 模式）— 无 firmware_version（pushMode=1 在 state）")
    @Test
    void osdNoFirmwareVersion() {
        Mavic3DroneOsdBuilder builder = new Mavic3DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx(DroneModel.MAVIC_3E));
        assertFalse(osd.containsKey("firmware_version"), "Mavic 3 firmware_version 是 pushMode=1（state topic）");
    }

    @DisplayName("TC-BUILDER-015：Mavic 3 DroneOsdBuilder 选择与字段集（Pilot 模式）— 无 type_subtype_gimbalindex")
    @Test
    void osdNoTypeSubtypeGimbalindex() {
        Mavic3DroneOsdBuilder builder = new Mavic3DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx(DroneModel.MAVIC_3E));
        assertFalse(osd.containsKey("type_subtype_gimbalindex"), "Mavic 3 属性列表未列 type_subtype_gimbalindex");
    }

    @DisplayName("TC-BUILDER-015：Mavic 3 DroneOsdBuilder 选择与字段集（Pilot 模式）— 共用字段")
    @Test
    void osdHasCommonFields() {
        Mavic3DroneOsdBuilder builder = new Mavic3DroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx(DroneModel.MAVIC_3E));
        // 共用字段（基类提供，Mavic 3 属性列表包含）
        assertNotNull(osd.get("mode_code"));
        assertNotNull(osd.get("gear"));
        assertNotNull(osd.get("latitude"));
        assertNotNull(osd.get("longitude"));
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
    }
}
