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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M4D/M4TD 飞行器 OSD 字段集构造器。
 * <p>M4D 家族特有字段：cameras 数组、type_subtype_gimbalindex（云台姿态+激光测距+红外测温）。</p>
 * <p>is_near_area_limit/is_near_height_limit 已提升到基类（M30/M3D/M4D 共有）。</p>
 * <p>wireless_link_topo（pushMode=1）在 state topic 上报，不在 OSD，由 DockOnlineService.publishDroneState() 推送。</p>
 * <p>红外字段（thermal_gain_mode/thermal_isotherm_state 等）按 {@link OsdContext#isThermal()} 条件上报：
 * M4TD（sub_type=1）上报，M4D（sub_type=0）不上报，避免平台解析异常。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m4d-properties.html">M4D/M4TD properties</a></p>
 */
@Component
public class M4DDroneOsdBuilder extends AbstractDroneOsdBuilder {

    @Override
    public String aircraftFamily() {
        return "m4d";
    }

    @Override
    public boolean supports(DeviceType droneType) {
        return droneType == DeviceType.M4D || droneType == DeviceType.M4TD;
    }

    @Override
    protected void appendDroneSpecific(OsdContext ctx, Map<String, Object> data) {
        OsdStrategy s = ctx.getStrategy();
        data.put(s.convertKey("cameras"), buildCameras(ctx));
        data.put(s.convertKey("type_subtype_gimbalindex"), buildGimbalInfo(ctx));
        // is_near_area_limit / is_near_height_limit 已提升到基类（M30/M3D/M4D 共有）
        // wireless_link_topo（pushMode=1）不在 OSD，由 publishDroneState() 在 state topic 推送
    }

    /**
     * 构造 type_subtype_gimbalindex 结构（云台姿态 + 激光测距 + 红外测温字段）。
     * <p>字段顺序对齐 M4D properties 文档：gimbal_pitch/roll/yaw → measure_target_* → payload_index → zoom_factor → thermal_*。</p>
     * <p>measure_target_* 为激光测距目标信息，模拟器不模拟测距场景，error_state=3（NO_SIGNAL），其余为 0。</p>
     * <p>thermal_* 字段仅 thermal 机型（M4TD）上报，M4D 不上报。</p>
     * <p>核实依据：M4D properties 文档 type_subtype_gimbalindex 结构（pushMode=0, r）。</p>
     */
    private Map<String, Object> buildGimbalInfo(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        PayloadType camera = PayloadType.defaultCameraFor(ctx.getDroneType());
        Map<String, Object> m = new LinkedHashMap<>();
        // 云台姿态字段
        m.put(s.convertKey("gimbal_pitch"), 0.0);
        m.put(s.convertKey("gimbal_roll"), 0.0);
        m.put(s.convertKey("gimbal_yaw"), 0.0);
        // 激光测距目标字段（模拟器不模拟测距，error_state=3=NO_SIGNAL）
        m.put(s.convertKey("measure_target_longitude"), 0.0);    // 激光测距目标经度
        m.put(s.convertKey("measure_target_latitude"), 0.0);     // 激光测距目标纬度
        m.put(s.convertKey("measure_target_altitude"), 0.0);     // 激光测距目标海拔（米）
        m.put(s.convertKey("measure_target_distance"), 0.0);     // 激光测距距离（米）
        m.put(s.convertKey("measure_target_error_state"), 3);    // 3=NO_SIGNAL（无信号/无目标）
        // 负载标识
        m.put(s.convertKey("payload_index"), camera != null ? camera.cameraIndex() : "99-0-0");
        m.put(s.convertKey("zoom_factor"), 2.0);
        // 红外测温字段（仅 thermal 机型上报）
        if (ctx.isThermal()) {
            m.put(s.convertKey("thermal_current_palette_style"), 0);  // 白热
            m.put(s.convertKey("thermal_gain_mode"), 0);              // 自动
            m.put(s.convertKey("thermal_isotherm_state"), 0);         // 关闭
            m.put(s.convertKey("thermal_isotherm_upper_limit"), 50);
            m.put(s.convertKey("thermal_isotherm_lower_limit"), 20);
            m.put(s.convertKey("thermal_global_temperature_min"), 20.0);
            m.put(s.convertKey("thermal_global_temperature_max"), 50.0);
        }
        return m;
    }
}
