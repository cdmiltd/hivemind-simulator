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

package ltd.cdmi.hivemind.simulator.device.osd;

import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.telemetry.StateField;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mavic 3 行业系列飞行器 state 字段集构造器（Pilot 模式）。
 * <p>支持 Mavic 3E / Mavic 3T，对齐 DJI Mavic 3 行业系列设备属性列表（pushMode=1 字段集）。</p>
 * <p>与 Matrice 4 系列的差异：
 * <ul>
 *   <li>包含 firmware_version（Mavic 3 pushMode=1，在 state topic 上报）</li>
 *   <li>不含 commander_flight_*、rth_mode、offline_map_enable（Mavic 3 属性列表未列）</li>
 * </ul>
 * <p>核实依据：用户提供的 Mavic 3 行业系列设备属性列表（pushMode=1 字段集）</p>
 */
@Component
public class Mavic3StateBuilder extends AbstractDroneStateBuilder {

    @Override
    public String aircraftFamily() {
        return "mavic3";
    }

    @Override
    public boolean supports(DroneModel droneType) {
        return droneType == DroneModel.MAVIC_3E
                || droneType == DroneModel.MAVIC_3T;
    }

    @Override
    public Map<String, Object> buildDroneState(RuntimeConfig runtimeConfig) {
        Map<String, Object> data = new LinkedHashMap<>();

        // mode_code_reason — 飞行器进入当前状态的原因（pushMode=1, r）
        data.put(StateField.MODE_CODE_REASON.fieldName(), 0);  // 0=无意义

        // dongle_infos — 4G Dongle 信息（pushMode=1, r）
        data.put(StateField.DONGLE_INFOS.fieldName(), buildDongleInfos());

        // serious_low_battery_warning_threshold — 严重低电量告警（pushMode=1, r）
        data.put(StateField.SERIOUS_LOW_BATTERY_WARNING_THRESHOLD.fieldName(), 20);

        // low_battery_warning_threshold — 低电量告警（pushMode=1, r）
        data.put(StateField.LOW_BATTERY_WARNING_THRESHOLD.fieldName(), 50);

        // control_source — 当前控制源（pushMode=1, r）
        data.put(StateField.CONTROL_SOURCE.fieldName(), "A");

        // home_latitude / home_longitude — Home 点位置（pushMode=1, r）
        data.put(StateField.HOME_LATITUDE.fieldName(), runtimeConfig.getLocationLatitude());
        data.put(StateField.HOME_LONGITUDE.fieldName(), runtimeConfig.getLocationLongitude());

        // firmware_upgrade_status — 固件升级状态（pushMode=1, r）
        data.put(StateField.FIRMWARE_UPGRADE_STATUS.fieldName(), 0);  // 0=未升级

        // compatible_status — 固件一致性（pushMode=1, r）
        data.put(StateField.COMPATIBLE_STATUS.fieldName(), 0);  // 0=不需要一致性升级

        // firmware_version — 固件版本（Mavic 3 pushMode=1, r）
        data.put(StateField.FIRMWARE_VERSION.fieldName(), "0.0.0.0");

        // camera_watermark_settings — 相机水印设置（pushMode=1, rw）
        data.put(StateField.CAMERA_WATERMARK_SETTINGS.fieldName(), buildCameraWatermarkSettings());

        return data;
    }
}
