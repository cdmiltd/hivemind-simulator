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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mavic3StateBuilder 单元测试（TC-ONLINE-010）。
 * <p>验证 Mavic 3 行业系列 Pilot 模式飞行器 state 字段集：
 * 包含 firmware_version（pushMode=1），不含 commander_flight_*、rth_mode、offline_map_enable（Matrice 4 系列特有）。
 * <p>核实依据：用户提供的 Mavic 3 行业系列设备属性列表（pushMode=1 字段集）。
 */
class Mavic3StateBuilderTest {

    private Mavic3StateBuilder builder;
    private RuntimeConfig runtimeConfig;

    @BeforeEach
    void setUp() {
        builder = new Mavic3StateBuilder();
        runtimeConfig = Mockito.mock(RuntimeConfig.class);
        Mockito.when(runtimeConfig.getLocationLatitude()).thenReturn(22.5);
        Mockito.when(runtimeConfig.getLocationLongitude()).thenReturn(113.9);
    }

    @Test
    void supportsMavic3EAnd3T() {
        assertTrue(builder.supports(DeviceType.MAVIC_3E));
        assertTrue(builder.supports(DeviceType.MAVIC_3T));
        assertFalse(builder.supports(DeviceType.M4E));
        assertFalse(builder.supports(DeviceType.M400));
    }

    @Test
    void buildDroneStateContainsAllPushMode1Fields() {
        Map<String, Object> data = builder.buildDroneState(runtimeConfig);

        assertTrue(data.containsKey("mode_code_reason"));
        assertTrue(data.containsKey("dongle_infos"));
        assertTrue(data.containsKey("serious_low_battery_warning_threshold"));
        assertTrue(data.containsKey("low_battery_warning_threshold"));
        assertTrue(data.containsKey("control_source"));
        assertTrue(data.containsKey("home_latitude"));
        assertTrue(data.containsKey("home_longitude"));
        assertTrue(data.containsKey("firmware_upgrade_status"));
        assertTrue(data.containsKey("compatible_status"));
        assertTrue(data.containsKey("firmware_version"), "Mavic 3 firmware_version pushMode=1，应在 state 上报");
        assertTrue(data.containsKey("camera_watermark_settings"));
    }

    @Test
    void buildDroneStateExcludesMatrice4SpecificFields() {
        Map<String, Object> data = builder.buildDroneState(runtimeConfig);
        assertFalse(data.containsKey("offline_map_enable"), "Mavic 3 属性列表未列 offline_map_enable");
        assertFalse(data.containsKey("current_rth_mode"), "Mavic 3 属性列表未列 current_rth_mode");
        assertFalse(data.containsKey("rth_mode"), "Mavic 3 属性列表未列 rth_mode");
        assertFalse(data.containsKey("commander_flight_height"), "Mavic 3 属性列表未列 commander_flight_height");
        assertFalse(data.containsKey("commander_flight_mode"), "Mavic 3 属性列表未列 commander_flight_mode");
        assertFalse(data.containsKey("current_commander_flight_mode"), "Mavic 3 属性列表未列 current_commander_flight_mode");
        assertFalse(data.containsKey("commander_mode_lost_action"), "Mavic 3 属性列表未列 commander_mode_lost_action");
    }

    @Test
    void homeLatLonFromRuntimeConfig() {
        Map<String, Object> data = builder.buildDroneState(runtimeConfig);
        assertEquals(22.5, data.get("home_latitude"));
        assertEquals(113.9, data.get("home_longitude"));
    }
}
