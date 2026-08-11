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

package ltd.cdmi.simulator.device;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M4D/M4TD 飞行器 OSD 字段集构造器。
 * <p>M4D 家族特有字段：cameras 数组、type_subtype_gimbalindex（云台姿态+红外测温）、wireless_link_topo。</p>
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
        data.put(s.convertKey("wireless_link_topo"), buildWirelessLinkTopo(s));
    }

    /**
     * 构造 cameras 数组（飞行器相机信息）。
     * <p>红外相关字段（ir_zoom_factor/ir_metering_mode 等）按 {@link OsdContext#isThermal()} 条件上报。</p>
     */
    private List<Map<String, Object>> buildCameras(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        PayloadType camera = PayloadType.defaultCameraFor(ctx.getDroneType());
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> cam = new LinkedHashMap<>();
        cam.put(s.convertKey("payload_index"), camera != null ? camera.cameraIndex() : "99-0-0");
        cam.put(s.convertKey("camera_mode"), 0);           // 拍照
        cam.put(s.convertKey("photo_state"), 0);            // 空闲
        cam.put(s.convertKey("recording_state"), 0);        // 空闲
        cam.put(s.convertKey("zoom_factor"), 2.0);
        cam.put(s.convertKey("remain_photo_num"), 1000);
        cam.put(s.convertKey("remain_record_duration"), 3600);
        cam.put(s.convertKey("record_time"), 0);
        cam.put(s.convertKey("screen_split_enable"), 0);
        // 红外相关字段（仅 thermal 机型上报）
        if (ctx.isThermal()) {
            cam.put(s.convertKey("ir_zoom_factor"), 2.0);
            cam.put(s.convertKey("ir_metering_mode"), 0);   // 关闭测温
        }
        list.add(cam);
        return list;
    }

    /**
     * 构造 type_subtype_gimbalindex 结构（云台姿态 + 红外测温字段）。
     * <p>thermal_* 字段仅 thermal 机型（M4TD）上报，M4D 不上报。</p>
     * <p>核实依据：M4D properties 文档中 thermal_* 字段位于 type_subtype_gimbalindex 结构下。</p>
     */
    private Map<String, Object> buildGimbalInfo(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        PayloadType camera = PayloadType.defaultCameraFor(ctx.getDroneType());
        Map<String, Object> m = new LinkedHashMap<>();
        // 共用云台姿态字段
        m.put(s.convertKey("gimbal_pitch"), 0.0);
        m.put(s.convertKey("gimbal_roll"), 0.0);
        m.put(s.convertKey("gimbal_yaw"), 0.0);
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

    /**
     * 构造 wireless_link_topo（图传连接拓扑）。
     * <p>M4D 家族特有字段，M30 家族无此字段。</p>
     */
    private Map<String, Object> buildWirelessLinkTopo(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        // center_node：飞行器对频信息
        Map<String, Object> centerNode = new LinkedHashMap<>();
        centerNode.put(s.convertKey("sdr_id"), 0);
        centerNode.put(s.convertKey("sn"), "");
        m.put(s.convertKey("center_node"), centerNode);
        // leaf_nodes：机场对频信息（空数组，单机场场景）
        m.put(s.convertKey("leaf_nodes"), new ArrayList<>());
        return m;
    }
}
