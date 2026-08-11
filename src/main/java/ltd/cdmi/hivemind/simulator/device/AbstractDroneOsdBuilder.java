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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞行器 OSD Builder 抽象基类，采用模板方法模式。
 * <p>提供所有机型共用的基础飞行字段（位置、姿态、速度、电池、定位、负载标识），
 * 子类通过 {@link #appendDroneSpecific(OsdContext, Map)} 追加机型特有字段（如 cameras 数组、wireless_link_topo 等）。</p>
 * <p>红外字段（thermal_*）由子类通过 {@link OsdContext#isThermal()} 条件判断是否追加。</p>
 */
public abstract class AbstractDroneOsdBuilder implements DroneOsdBuilder {

    @Override
    public final Map<String, Object> buildDroneOsd(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        DeviceState state = ctx.getState();
        Map<String, Object> data = new LinkedHashMap<>();
        // 共用基础飞行字段（所有机型都上报）
        data.put(s.convertKey("mode_code"), state.getDroneModeCode());
        data.put(s.convertKey("latitude"), state.getDroneLatitude());
        data.put(s.convertKey("longitude"), state.getDroneLongitude());
        data.put(s.convertKey("altitude"), state.getDroneElevation());
        data.put(s.convertKey("height"), state.getDroneHeight());
        data.put(s.convertKey("elevation"), state.getDroneElevation());
        data.put(s.convertKey("attitude_pitch"), state.getAttitudePitch());
        data.put(s.convertKey("attitude_roll"), state.getAttitudeRoll());
        data.put(s.convertKey("attitude_head"), state.getAttitudeYaw());
        data.put(s.convertKey("horizontal_speed"), state.getHorizontalSpeed());
        data.put(s.convertKey("vertical_speed"), state.getVerticalSpeed());
        data.put(s.convertKey("wind_speed"), state.getWindSpeed());
        data.put(s.convertKey("wind_direction"), state.getWindDirection());
        data.put(s.convertKey("battery"), buildBattery(ctx));
        data.put(s.convertKey("position_state"), buildPositionState(ctx));
        data.put(s.convertKey("control_mode"), state.getControlMode());
        data.put(s.convertKey("flight_time"), state.getFlightTimeSeconds());
        // 负载标识（所有机型都上报主相机信息）
        PayloadType camera = PayloadType.defaultCameraFor(ctx.getRuntimeConfig().getDroneType());
        if (camera != null) {
            data.put(s.convertKey("current_camera_type"), camera.getType());
            data.put(s.convertKey("camera_index"), camera.cameraIndex());
        }
        // 机型特有字段
        appendDroneSpecific(ctx, data);
        return data;
    }

    /**
     * 子类实现，追加该机型特有的 OSD 字段（如 cameras 数组、wireless_link_topo、payloads 等）。
     *
     * @param ctx  OSD 上下文
     * @param data 已填充共用字段的 Map，子类直接往里 put 特有字段
     */
    protected abstract void appendDroneSpecific(OsdContext ctx, Map<String, Object> data);

    // ==================== 共用子结构构造 ====================

    protected Map<String, Object> buildBattery(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        DeviceState state = ctx.getState();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("capacity_percent"), state.getBatteryPercent());
        m.put(s.convertKey("remain_flight_time"), Math.max(0, state.getBatteryPercent() * 30L / 100));
        m.put(s.convertKey("voltage"), state.getBatteryVoltage());
        m.put(s.convertKey("temperature"), state.getBatteryTemperature());
        return m;
    }

    protected Map<String, Object> buildPositionState(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        DeviceState state = ctx.getState();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("gps_number"), 18);
        m.put(s.convertKey("is_fixed"), state.getPositionState());
        m.put(s.convertKey("quality"), 5);
        m.put(s.convertKey("gps_number_in_rtcm"), 18);
        m.put(s.convertKey("rtcm_number"), 6);
        return m;
    }
}
