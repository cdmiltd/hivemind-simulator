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
 * M4StateBuilder 单元测试（TC-ONLINE-011）。
 * <p>验证 DJI Matrice 4 系列（M400/M4E/M4T）Pilot 模式飞行器 state 字段集：
 * 包含 commander_flight_*、rth_mode、offline_map_enable 等 pushMode=1 字段，
 * 不含 firmware_version（pushMode=0，在 OSD 上报）、不含 payloads/wpmz_version/psdk_*（属性列表未列）。
 * <p>核实依据：用户提供的 DJI Matrice 4 系列设备属性列表（pushMode=1 字段集，第一部分+第二部分合并）。
 */
class M4StateBuilderTest {

    private M4StateBuilder builder;
    private RuntimeConfig runtimeConfig;

    @BeforeEach
    void setUp() {
        builder = new M4StateBuilder();
        runtimeConfig = Mockito.mock(RuntimeConfig.class);
        Mockito.when(runtimeConfig.getLocationLatitude()).thenReturn(22.5);
        Mockito.when(runtimeConfig.getLocationLongitude()).thenReturn(113.9);
    }

    @Test
    void supportsM400M4EM4T() {
        assertTrue(builder.supports(DeviceType.M400), "M400 与 M4E/M4T state 字段集一致，复用 M4StateBuilder");
        assertTrue(builder.supports(DeviceType.M4E));
        assertTrue(builder.supports(DeviceType.M4T));
        assertFalse(builder.supports(DeviceType.MAVIC_3E));
        assertFalse(builder.supports(DeviceType.M4D));
    }

    @Test
    void buildDroneStateContainsAllPushMode1Fields() {
        Map<String, Object> data = builder.buildDroneState(runtimeConfig);

        // 第一部分 pushMode=1 字段
        assertTrue(data.containsKey("offline_map_enable"), "应包含 offline_map_enable");
        assertTrue(data.containsKey("dongle_infos"), "应包含 dongle_infos");
        assertTrue(data.containsKey("current_rth_mode"), "应包含 current_rth_mode");
        assertTrue(data.containsKey("rth_mode"), "应包含 rth_mode");
        assertTrue(data.containsKey("serious_low_battery_warning_threshold"));
        assertTrue(data.containsKey("low_battery_warning_threshold"));
        assertTrue(data.containsKey("control_source"));
        assertTrue(data.containsKey("home_latitude"));
        assertTrue(data.containsKey("home_longitude"));
        assertTrue(data.containsKey("firmware_upgrade_status"));

        // 第二部分 pushMode=1 字段
        assertTrue(data.containsKey("compatible_status"));
        assertTrue(data.containsKey("mode_code_reason"));
        assertTrue(data.containsKey("commander_flight_height"));
        assertTrue(data.containsKey("commander_flight_mode"));
        assertTrue(data.containsKey("current_commander_flight_mode"));
        assertTrue(data.containsKey("commander_mode_lost_action"));
        assertTrue(data.containsKey("camera_watermark_settings"));
    }

    @Test
    void buildDroneStateExcludesFirmwareVersion() {
        Map<String, Object> data = builder.buildDroneState(runtimeConfig);
        assertFalse(data.containsKey("firmware_version"),
                "Matrice 4 系列 firmware_version pushMode=0，应在 OSD 上报，不应在 state");
    }

    @Test
    void buildDroneStateExcludesPayloadAndPsdkFields() {
        Map<String, Object> data = builder.buildDroneState(runtimeConfig);
        assertFalse(data.containsKey("payloads"), "Matrice 4 系列属性列表未列 payloads");
        assertFalse(data.containsKey("wpmz_version"), "Matrice 4 系列属性列表未列 wpmz_version");
        assertFalse(data.containsKey("psdk_ui_resource"), "Matrice 4 系列属性列表未列 psdk_ui_resource");
        assertFalse(data.containsKey("psdk_widget_values"), "Matrice 4 系列属性列表未列 psdk_widget_values");
    }

    @Test
    void homeLatLonFromRuntimeConfig() {
        Map<String, Object> data = builder.buildDroneState(runtimeConfig);
        assertEquals(22.5, data.get("home_latitude"));
        assertEquals(113.9, data.get("home_longitude"));
    }

    @Test
    void dongleInfosStructureMatchesDjiSpec() {
        Map<String, Object> data = builder.buildDroneState(runtimeConfig);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> dongleInfos = (java.util.List<Map<String, Object>>) data.get("dongle_infos");
        assertNotNull(dongleInfos);
        assertEquals(1, dongleInfos.size(), "默认 1 个 Dongle");

        Map<String, Object> dongle = dongleInfos.get(0);
        assertTrue(dongle.containsKey("imei"));
        assertTrue(dongle.containsKey("dongle_type"));
        assertTrue(dongle.containsKey("eid"));
        assertTrue(dongle.containsKey("esim_activate_state"));
        assertTrue(dongle.containsKey("sim_card_state"));
        assertTrue(dongle.containsKey("sim_slot"));
        assertTrue(dongle.containsKey("esim_infos"));
        assertTrue(dongle.containsKey("sim_info"));
    }

    @Test
    void cameraWatermarkSettingsStructureMatchesDjiSpec() {
        Map<String, Object> data = builder.buildDroneState(runtimeConfig);
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) data.get("camera_watermark_settings");
        assertNotNull(settings);
        assertTrue(settings.containsKey("global_enable"));
        assertTrue(settings.containsKey("drone_type_enable"));
        assertTrue(settings.containsKey("drone_sn_enable"));
        assertTrue(settings.containsKey("datetime_enable"));
        assertTrue(settings.containsKey("gps_enable"));
        assertTrue(settings.containsKey("user_custom_string_enable"));
        assertTrue(settings.containsKey("user_custom_string"));
        assertTrue(settings.containsKey("layout"));
    }
}
