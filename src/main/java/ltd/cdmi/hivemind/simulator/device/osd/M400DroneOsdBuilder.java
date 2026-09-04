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
import ltd.cdmi.hivemind.simulator.device.DefaultCameraResolver;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M400/M4E/M4T 飞行器 OSD 字段集构造器（Pilot 模式）。
 * <p>支持 Matrice 400、DJI Matrice 4E、DJI Matrice 4T 三款 Pilot 上云飞行器。
 * 三者设备属性列表字段集完全一致，共用此 Builder。</p>
 * <p>各机型默认相机（DJI 产品支持文档）：
 * <ul>
 *   <li>M400 → H30（82-0-0，非热成像）</li>
 *   <li>M4E → DJI Matrice 4E Camera（88-0-0）</li>
 *   <li>M4T → DJI Matrice 4T Camera（89-0-0）</li>
 * </ul>
 * <p>与 M4D 差异：
 * <ul>
 *   <li>type_subtype_gimbalindex 简化：仅 gimbal_pitch/roll/yaw + payload_index + zoom_factor（无 measure_target_*、thermal_*）</li>
 *   <li>无 cameras 数组</li>
 *   <li>无 distance_limit_status/rth_altitude（属性列表未列，覆盖 includeDistanceLimitFields 为 false）</li>
 *   <li>mode_code/gear/firmware_version 始终上报（pushMode=0 基础飞行字段，M400 属性列表第二部分确认）</li>
 * </ul>
 * <p>参考：DJI Cloud API Pilot to Cloud 设备属性文档（用户提供的 M400/M4E/M4T 属性列表核实）</p>
 */
@Component
public class M400DroneOsdBuilder extends AbstractDroneOsdBuilder {

    @Override
    public String aircraftFamily() {
        return "m400-m4e-m4t";
    }

    @Override
    public boolean supports(DroneModel droneType) {
        return droneType == DroneModel.M400
                || droneType == DroneModel.M4E
                || droneType == DroneModel.M4T;
    }

    @Override
    protected boolean includeDistanceLimitFields() {
        // M400 Pilot 属性列表未列 distance_limit_status/rth_altitude
        return false;
    }

    @Override
    protected boolean includeFirmwareVersionInOsd() {
        // M400 Pilot 属性列表 firmware_version pushMode=0（OSD），其他机型 pushMode=1（state topic）
        return true;
    }

    @Override
    protected void appendDroneSpecific(OsdContext ctx, Map<String, Object> data) {
        // 负载属性 key 必须为负载索引枚举值（如 M400→"82-0-0"），与 struct 内 payload_index 字段数值一致。
        // 文档 Column 名 type_subtype_gimbalindex 是 {type-subtype-gimbalindex} 占位符，禁止作字面量 key。
        // 核实依据：Pilot properties 占位符写法"与字段 payload_index 数值一致"（TC-PAYLOAD-027）
        data.put(DefaultCameraResolver.requireCameraIndex(ctx.getSelectedPayload(), ctx.getDroneType(), "M400 负载属性 key"),
                buildGimbalInfo(ctx));
    }

    /**
     * 构造 type_subtype_gimbalindex 结构（云台姿态）。
     * <p>M400/M4E/M4T 结构简化（对比 M4D）：仅 gimbal_pitch/roll/yaw + payload_index + zoom_factor，
     * 无 measure_target_*（激光测距）、无 thermal_*（红外测温）。</p>
     * <p>payload_index 按机型动态获取（DJI 产品支持文档）：
     * M400→82-0-0(H30), M4E→88-0-0(M4E Camera), M4T→89-0-0(M4T Camera)。</p>
     * <p>struct 的 key 为负载索引枚举值（appendDroneSpecific 中由 requireCameraIndex 生成），
     * 文档 Column 名 type_subtype_gimbalindex 是 {type-subtype-gimbalindex} 占位符。</p>
     * <p>核实依据：Pilot 设备属性列表 {type-subtype-gimbalindex} 结构，描述"与字段 payload_index 数值一致"（pushMode=0, r，TC-PAYLOAD-027）。</p>
     */
    private Map<String, Object> buildGimbalInfo(OsdContext ctx) {
        // 统一用 ctx.getSelectedPayload()（支持用户选择的负载覆盖，M400 可挂载 H20/H30/H30T 等通用云台）
        PayloadType camera = ctx.getSelectedPayload();
        DeviceState state = ctx.getState();
        Map<String, Object> m = new LinkedHashMap<>();
        // 云台姿态字段——从 state 读取，与 cameras 数组同源
        m.put("gimbal_pitch", state.getGimbalPitch());
        m.put("gimbal_roll", state.getGimbalRoll());
        m.put("gimbal_yaw", state.getGimbalYaw());
        m.put("payload_index", DefaultCameraResolver.requireCameraIndex(camera, ctx.getDroneType(), "M400 type_subtype_gimbalindex"));
        // 变焦倍数——从 state 读取，由 DRC 变焦指令更新
        m.put("zoom_factor", state.getZoomFactor());
        return m;
    }
}
