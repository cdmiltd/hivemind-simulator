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
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DJI Matrice 4 系列（M400/M4E/M4T）飞行器 state 字段集构造器（Pilot 模式）。
 * <p>支持 M400 / M4E / M4T，三者 pushMode=1 字段集一致，共用此 Builder。</p>
 * <p>对齐 DJI Matrice 4 系列设备属性列表（pushMode=1 字段集，第一部分+第二部分合并）。</p>
 * <p>与 Mavic 3 的差异：
 * <ul>
 *   <li>不含顶层 firmware_version（Matrice 4 系列 pushMode=0，在 OSD 主题上报）</li>
 *   <li>包含 commander_flight_*、current_rth_mode、rth_mode、offline_map_enable（Matrice 4 系列属性列表 pushMode=1）</li>
 * </ul>
 * <p>核实依据：用户提供的 DJI Matrice 4 系列设备属性列表（pushMode=1 字段集）；M400 属性列表确认无 payloads/wpmz_version/psdk_* 字段</p>
 */
@Component
public class M4StateBuilder extends AbstractDroneStateBuilder {

    @Override
    public String aircraftFamily() {
        return "m400-m4e-m4t";
    }

    @Override
    public boolean supports(DeviceType droneType) {
        return droneType == DeviceType.M400
                || droneType == DeviceType.M4E
                || droneType == DeviceType.M4T;
    }

    @Override
    public Map<String, Object> buildDroneState(RuntimeConfig runtimeConfig) {
        Map<String, Object> data = new LinkedHashMap<>();

        // offline_map_enable — 离线地图开关（pushMode=1, r）
        data.put("offline_map_enable", 0);  // 0=关闭

        // dongle_infos — 4G Dongle 信息（pushMode=1, r）
        data.put("dongle_infos", buildDongleInfos());

        // current_rth_mode — 返航高度模式当前值（pushMode=1, r）
        data.put("current_rth_mode", 1);    // 1=设定高度

        // rth_mode — 返航高度模式设置值（pushMode=1, r）
        data.put("rth_mode", 1);            // 1=设定高度

        // serious_low_battery_warning_threshold — 严重低电量告警（pushMode=1, r）
        data.put("serious_low_battery_warning_threshold", 20);

        // low_battery_warning_threshold — 低电量告警（pushMode=1, r）
        data.put("low_battery_warning_threshold", 50);

        // control_source — 当前控制源（pushMode=1, r）
        data.put("control_source", "A");

        // home_latitude / home_longitude — Home 点位置（pushMode=1, r）
        data.put("home_latitude", runtimeConfig.getLocationLatitude());
        data.put("home_longitude", runtimeConfig.getLocationLongitude());

        // firmware_upgrade_status — 固件升级状态（pushMode=1, r）
        data.put("firmware_upgrade_status", 0);  // 0=未升级

        // compatible_status — 固件一致性（pushMode=1, r）
        data.put("compatible_status", 0);  // 0=不需要一致性升级

        // mode_code_reason — 飞行器进入当前状态的原因（pushMode=1, r）
        data.put("mode_code_reason", 0);  // 0=无意义

        // commander_flight_height — 指点飞行高度（pushMode=1, rw）
        data.put("commander_flight_height", 100);  // 米

        // commander_flight_mode — 指点飞行模式设置值（pushMode=1, rw）
        data.put("commander_flight_mode", 0);           // 0=智能高度飞行

        // current_commander_flight_mode — 指点飞行模式当前值（pushMode=1, r）
        data.put("current_commander_flight_mode", 0);   // 0=智能高度飞行

        // commander_mode_lost_action — 指点飞行失控动作（pushMode=1, rw）
        data.put("commander_mode_lost_action", 0);      // 0=继续执行指点飞行任务

        // camera_watermark_settings — 相机水印设置（pushMode=1, rw）
        data.put("camera_watermark_settings", buildCameraWatermarkSettings());

        return data;
    }
}
