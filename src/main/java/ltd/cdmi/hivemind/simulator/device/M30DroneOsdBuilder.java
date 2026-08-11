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
 * M30/M30T 飞行器 OSD 字段集构造器。
 * <p>M30 家族特有字段：payloads（负载状态数组）、distance_limit_status（限远状态）、
 * rth_altitude（返航高度）、rc_lost_action（遥控器失控动作）、cameras 数组。</p>
 * <p>不含 M4D 家族的 wireless_link_topo/type_subtype_gimbalindex（M30 无此结构）。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html">M30/M30T properties</a></p>
 */
@Component
public class M30DroneOsdBuilder extends AbstractDroneOsdBuilder {

    @Override
    public String aircraftFamily() {
        return "m30";
    }

    @Override
    public boolean supports(DeviceType droneType) {
        return droneType == DeviceType.M30 || droneType == DeviceType.M30T;
    }

    @Override
    protected void appendDroneSpecific(OsdContext ctx, Map<String, Object> data) {
        OsdStrategy s = ctx.getStrategy();
        data.put(s.convertKey("payloads"), buildPayloads(ctx));
        data.put(s.convertKey("distance_limit_status"), buildDistanceLimitStatus(s));
        data.put(s.convertKey("rth_altitude"), 100);       // 返航高度（米）
        data.put(s.convertKey("rc_lost_action"), 2);        // 返航
        data.put(s.convertKey("cameras"), buildCameras(ctx));
    }

    /**
     * 构造 payloads 数组（负载状态信息）。
     * <p>M30 家族特有字段，M4D 家族无此字段（M4D 用 cameras + type_subtype_gimbalindex）。</p>
     */
    private List<Map<String, Object>> buildPayloads(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        PayloadType camera = PayloadType.defaultCameraFor(ctx.getDroneType());
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(s.convertKey("payload_index"), camera != null ? camera.cameraIndex() : "52-0-0");
        payload.put(s.convertKey("control_source"), "A");
        payload.put(s.convertKey("firmware_version"), "01.00.0000");
        payload.put(s.convertKey("sn"), " simulated-payload-001");
        list.add(payload);
        return list;
    }

    /**
     * 构造 distance_limit_status（飞行器限远状态）。
     */
    private Map<String, Object> buildDistanceLimitStatus(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("state"), 1);                  // 已设置
        m.put(s.convertKey("distance_limit"), 8000);      // 限远距离（米）
        m.put(s.convertKey("is_near_distance_limit"), 0); // 未接近
        return m;
    }

    /**
     * 构造 cameras 数组（飞行器相机信息）。
     * <p>M30 的 cameras 结构与 M4D 类似，但不嵌套在 type_subtype_gimbalindex 中。</p>
     */
    private List<Map<String, Object>> buildCameras(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        PayloadType camera = PayloadType.defaultCameraFor(ctx.getDroneType());
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> cam = new LinkedHashMap<>();
        cam.put(s.convertKey("payload_index"), camera != null ? camera.cameraIndex() : "52-0-0");
        cam.put(s.convertKey("camera_mode"), 0);
        cam.put(s.convertKey("photo_state"), 0);
        cam.put(s.convertKey("recording_state"), 0);
        cam.put(s.convertKey("zoom_factor"), 2.0);
        cam.put(s.convertKey("remain_photo_num"), 1000);
        cam.put(s.convertKey("remain_record_duration"), 3600);
        cam.put(s.convertKey("record_time"), 0);
        list.add(cam);
        return list;
    }
}
