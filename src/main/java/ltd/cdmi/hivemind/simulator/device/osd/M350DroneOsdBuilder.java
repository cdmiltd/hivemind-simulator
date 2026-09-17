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
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Matrice 350 RTK / Matrice 300 RTK 飞行器 OSD 字段集构造器（Pilot 模式）。
 * <p>支持 M350_RTK / M300_RTK，网关设备为 DJI RC Plus / DJI 带屏遥控器行业版。</p>
 * <p>字段集 = 基础飞行字段（基类提供）+ {@code {type-subtype-gimbalindex}} 负载属性结构。</p>
 * <p>与 Mavic 3 / M30 / M400 的差异：
 * <ul>
 *   <li>无 country（Mavic 3 独有）</li>
 *   <li>无 cameras 数组（M30 独有，M350/M300 通过 {type-subtype-gimbalindex} 结构上报负载信息）</li>
 *   <li>无 rid_state / rc_lost_action（M30 独有）</li>
 *   <li>无 distance_limit_status / rth_altitude（属性列表未列，覆盖 includeDistanceLimitFields=false）</li>
 *   <li>firmware_version pushMode=1（state topic），不在 OSD 上报</li>
 * </ul>
 * <p>负载属性结构通过 {@link AbstractDroneOsdBuilder#buildPayload} 构造，
 * 含 gimbal_pitch/roll/yaw、measure_target_*、zoom_factor、thermal_*（仅热成像机型）。</p>
 * <p>核实依据：DJI 官方文档 RC Plus properties 飞行器 OSD 示例（M300 RTK + H20T）；
 * 用户提供的"其他机型-飞行器"设备属性列表（pushMode=0 字段集）。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/rc-plus/properties.html">RC Plus 设备属性</a></p>
 */
@Component
public class M350DroneOsdBuilder extends AbstractDroneOsdBuilder {

    @Override
    public String aircraftFamily() {
        return "m350-m300";
    }

    @Override
    public boolean supports(DroneModel droneType) {
        return droneType == DroneModel.M350_RTK
                || droneType == DroneModel.M300_RTK;
    }

    @Override
    protected boolean includeDistanceLimitFields() {
        // M350/M300 属性列表未列 distance_limit_status/rth_altitude
        return false;
    }

    @Override
    protected void appendDroneSpecific(OsdContext ctx, Map<String, Object> data) {
        // 负载属性（以负载索引为 key 的相机属性，pushMode=0 字段）
        // 含 gimbal_pitch/roll/yaw、measure_target_*、zoom_factor、thermal_*（仅热成像机型）
        // 注：payload_index 是 pushMode=1（state topic），不在 OSD 中
        PayloadType camera = ctx.getSelectedPayload();
        if (camera != null) {
            data.put(camera.cameraIndex(), buildPayload(camera, ctx));
        }
    }
}
