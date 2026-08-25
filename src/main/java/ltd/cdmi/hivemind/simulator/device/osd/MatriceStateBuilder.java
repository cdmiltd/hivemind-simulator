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
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.hivemind.simulator.device.DefaultCameraResolver;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DJI Matrice 系列飞行器 state 字段集构造器（Pilot 模式）。
 * <p>覆盖 Matrice 350 RTK / Matrice 300 RTK / Matrice 30 / Matrice 30T，
 * 这些机型的 state（pushMode=1）字段集相同，因此共用此 Builder。
 * OSD（pushMode=0）字段集不同：M350/M300 由 {@link M350DroneOsdBuilder} 处理，
 * M30/M30T 由 {@link M30DroneOsdBuilder} 处理。</p>
 * <p>字段集 = Mavic 3 state 字段 + 负载相关字段：
 * <ul>
 *   <li>Mavic 3 共有字段：mode_code_reason、dongle_infos、battery thresholds、control_source、
 *       home_latitude/longitude、firmware_upgrade_status、compatible_status、firmware_version、camera_watermark_settings</li>
 *   <li>新增：{@code {type-subtype-gimbalindex}.payload_index}（pushMode=1, r）</li>
 * </ul>
 * <p>核实依据：用户提供的"其他机型-飞行器"设备属性列表（pushMode=1 字段集）；
 * DJI 官方文档 RC Plus properties 飞行器 state 示例。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/rc-plus/properties.html">RC Plus 设备属性</a></p>
 */
@Component
public class MatriceStateBuilder extends AbstractDroneStateBuilder {

    @Override
    public String aircraftFamily() {
        return "m350-m300-m30";
    }

    @Override
    public boolean supports(DroneModel droneType) {
        return droneType == DroneModel.M350_RTK
                || droneType == DroneModel.M300_RTK
                || droneType == DroneModel.M30
                || droneType == DroneModel.M30T;
    }

    @Override
    public Map<String, Object> buildDroneState(RuntimeConfig runtimeConfig) {
        Map<String, Object> data = new LinkedHashMap<>();

        // mode_code_reason — 飞行器进入当前状态的原因（pushMode=1, r）
        data.put(StateField.MODE_CODE_REASON.fieldName(), 0);  // 0=无意义

        // dongle_infos — 4G Dongle 信息（pushMode=1, r)
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

        // firmware_version — 固件版本（pushMode=1, r）
        data.put(StateField.FIRMWARE_VERSION.fieldName(), "0.0.0.0");

        // camera_watermark_settings — 相机水印设置（pushMode=1, rw）
        data.put(StateField.CAMERA_WATERMARK_SETTINGS.fieldName(), buildCameraWatermarkSettings());

        // 负载相关字段（以负载索引为 key 的结构）
        PayloadType camera = resolvePayload(runtimeConfig);
        if (camera != null) {
            Map<String, Object> payloadState = new LinkedHashMap<>();
            payloadState.put("payload_index", camera.cameraIndex());
            data.put(camera.cameraIndex(), payloadState);
        }

        return data;
    }

    /**
     * 解析当前生效的相机负载：优先用户选择，未选择时回退到飞行器默认主相机。
     */
    private PayloadType resolvePayload(RuntimeConfig runtimeConfig) {
        PayloadType selected = runtimeConfig.getSelectedPayload();
        if (selected != null) return selected;
        return DefaultCameraResolver.defaultCameraFor(runtimeConfig.getDroneType());
    }
}
