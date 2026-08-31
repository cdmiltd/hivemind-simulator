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
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * M30/M30T 飞行器 OSD 字段集构造器。
 * <p>M30 家族特有字段：rc_lost_action（遥控器失控动作）、country（国家区域码）、cameras 数组、rid_state（RID 工作状态）、
 * 负载索引 key 属性（gimbal_pitch/roll/yaw + measure_target_* + zoom_factor + thermal_*，M30 旧版方式）。</p>
 * <p>distance_limit_status/rth_altitude/is_near_area_limit/is_near_height_limit 已提升到基类（M30/M3D/M4D 共有）。</p>
 * <p>不含 M4D 家族的 wireless_link_topo/type_subtype_gimbalindex（M30 无此结构）。</p>
 * <p>payloads（负载状态数组，pushMode=1）不在 OSD，由 DockOnlineService.publishDroneState() 在 state topic 上报。</p>
 * <p>exit_wayline_when_rc_lost 为废弃字段，不上报。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html">M30/M30T properties</a></p>
 */
@Component
public class M30DroneOsdBuilder extends AbstractDroneOsdBuilder {

    @Override
    public String aircraftFamily() {
        return "m30";
    }

    @Override
    public boolean supports(DroneModel droneType) {
        return droneType == DroneModel.M30 || droneType == DroneModel.M30T;
    }

    @Override
    protected void appendDroneSpecific(OsdContext ctx, Map<String, Object> data) {
        data.put(OsdField.RC_LOST_ACTION.fieldName(), 2);        // 返航（pushMode=0, rw）
        data.put(OsdField.COUNTRY.fieldName(), "CN");            // 国家区域码（pushMode=0, r，M30 properties 文档明确列出）
        data.put(OsdField.CAMERAS.fieldName(), buildCameras(ctx));
        // rid_state — RID 工作状态（仅 M30/M30T 有，pushMode=0, r）
        data.put(OsdField.RID_STATE.fieldName(), true);          // true=正常

        // M30 负载属性（旧版方式）：以负载索引为 key 的相机属性（pushMode=0 字段）
        // 注：payload_index 是 pushMode=1（state topic），不在 OSD 中
        // 统一用 ctx.getSelectedPayload()（与 cameras 数组同源，支持用户选择的负载覆盖）
        PayloadType camera = ctx.getSelectedPayload();
        if (camera != null) {
            data.put(camera.cameraIndex(), buildPayload(camera, ctx));
        }
    }
}
