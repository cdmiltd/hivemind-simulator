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

import ltd.cdmi.dji.cloudapi.sdk.telemetry.OsdField;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.hivemind.simulator.device.DefaultCameraResolver;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
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
    public boolean supports(DroneModel droneType) {
        return droneType == DroneModel.M4D || droneType == DroneModel.M4TD;
    }

    @Override
    protected void appendDroneSpecific(OsdContext ctx, Map<String, Object> data) {
        data.put(OsdField.CAMERAS.fieldName(), buildCameras(ctx));
        data.put(OsdField.TYPE_SUBTYPE_GIMBALINDEX.fieldName(), buildGimbalInfo(ctx));
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
        // 统一用 ctx.getSelectedPayload()（与 cameras 数组同源，支持用户选择的负载覆盖）
        PayloadType camera = ctx.getSelectedPayload();
        DeviceState state = ctx.getState();
        Map<String, Object> m = new LinkedHashMap<>();
        // 云台姿态字段——从 state 读取，与 cameras 数组同源
        m.put("gimbal_pitch", state.getGimbalPitch());
        m.put("gimbal_roll", state.getGimbalRoll());
        m.put("gimbal_yaw", state.getGimbalYaw());
        // 激光测距目标字段（模拟器不模拟测距，error_state=3=NO_SIGNAL）
        m.put("measure_target_longitude", 0.0);    // 激光测距目标经度
        m.put("measure_target_latitude", 0.0);     // 激光测距目标纬度
        m.put("measure_target_altitude", 0.0);     // 激光测距目标海拔（米）
        m.put("measure_target_distance", 0.0);     // 激光测距距离（米）
        m.put("measure_target_error_state", 3);    // 3=NO_SIGNAL（无信号/无目标）
        // 负载标识
        m.put("payload_index", DefaultCameraResolver.requireCameraIndex(camera, ctx.getDroneType(), "M4D type_subtype_gimbalindex"));
        // 变焦倍数——从 state 读取，由 DRC 变焦指令更新
        m.put("zoom_factor", state.getZoomFactor());
        // 红外测温字段（仅 thermal 机型上报）
        if (ctx.isThermal()) {
            m.put("thermal_current_palette_style", 0);  // 白热
            m.put("thermal_gain_mode", 0);              // 自动
            m.put("thermal_isotherm_state", 0);         // 关闭
            m.put("thermal_isotherm_upper_limit", 50);
            m.put("thermal_isotherm_lower_limit", 20);
            m.put("thermal_global_temperature_min", 20.0);
            m.put("thermal_global_temperature_max", 50.0);
        }
        return m;
    }
}
