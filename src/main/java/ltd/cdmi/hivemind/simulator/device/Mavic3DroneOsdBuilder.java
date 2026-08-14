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

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Mavic 3 行业系列（Mavic 3E/3T）飞行器 OSD 字段集构造器（Pilot 模式）。
 * <p>网关设备为 DJI RC Pro 行业版（type=144），通过 DJI Pilot 2 上云。</p>
 * <p>与 M30/M4D 差异：
 * <ul>
 *   <li>有 country（国家区域码，pushMode=0, r）— M30/M4D 无</li>
 *   <li>无 distance_limit_status/rth_altitude（属性列表未列，覆盖 includeDistanceLimitFields=false）</li>
 *   <li>无 type_subtype_gimbalindex（属性列表未列，负载信息通过 cameras 数组上报）</li>
 *   <li>firmware_version 是 pushMode=1（state topic），不在 OSD 上报（includeFirmwareVersionInOsd=false）</li>
 * </ul>
 * <p>参考：DJI Cloud API Mavic 3 行业系列设备属性文档 + DJI 产品支持文档相机枚举值</p>
 * <p>核实依据：用户提供的 Mavic 3 行业系列设备属性列表（pushMode=0 字段集）</p>
 */
@Component
public class Mavic3DroneOsdBuilder extends AbstractDroneOsdBuilder {

    @Override
    public String aircraftFamily() {
        return "mavic3";
    }

    @Override
    public boolean supports(DeviceType droneType) {
        return droneType == DeviceType.MAVIC_3E || droneType == DeviceType.MAVIC_3T;
    }

    @Override
    protected boolean includeDistanceLimitFields() {
        // Mavic 3 属性列表未列 distance_limit_status/rth_altitude
        return false;
    }

    @Override
    protected void appendDroneSpecific(OsdContext ctx, Map<String, Object> data) {
        OsdStrategy s = ctx.getStrategy();
        // country — 国家区域码（pushMode=0, r, Mavic 3 行业系列独有）
        data.put(s.convertKey("country"), "CN");
        // cameras — 飞行器相机信息（pushMode=0, r, 完整相机数组）
        // payload_index: Mavic 3E Camera=66-0-0, Mavic 3T Camera=67-0-0（DJI 产品支持文档）
        // Mavic 3T（sub_type=1）为 thermal 机型，cameras 包含 ir_zoom_factor/ir_metering_* 字段
        data.put(s.convertKey("cameras"), buildCameras(ctx));
    }
}
