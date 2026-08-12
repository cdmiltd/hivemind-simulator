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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        // 字段对齐 DJI M30/M3D/M4D properties 文档：height=绝对高度(椭球面)，elevation=相对起飞点高度
        data.put(s.convertKey("mode_code"), state.getDroneModeCode());
        data.put(s.convertKey("latitude"), state.getDroneLatitude());
        data.put(s.convertKey("longitude"), state.getDroneLongitude());
        data.put(s.convertKey("height"), state.getDroneElevation());
        data.put(s.convertKey("elevation"), state.getDroneHeight());
        data.put(s.convertKey("attitude_pitch"), state.getAttitudePitch());
        data.put(s.convertKey("attitude_roll"), state.getAttitudeRoll());
        data.put(s.convertKey("attitude_head"), (int) state.getAttitudeYaw());  // int（M30/M3D/M4D 文档均为 int）
        data.put(s.convertKey("horizontal_speed"), state.getHorizontalSpeed());
        data.put(s.convertKey("vertical_speed"), state.getVerticalSpeed());
        data.put(s.convertKey("wind_speed"), state.getWindSpeed());
        data.put(s.convertKey("wind_direction"), state.getWindDirection());
        data.put(s.convertKey("battery"), buildBattery(ctx));
        data.put(s.convertKey("position_state"), buildPositionState(ctx));
        data.put(s.convertKey("total_flight_time"), (float) state.getFlightTimeSeconds());  // float（M30/M3D/M4D 文档均为 float）
        // TC-BUILDER-014：补齐飞行器 OSD 共用字段（对齐 DJI M4D/M30 文档 + 真机示例）
        data.put(s.convertKey("activation_time"), 1700000000);              // 飞行器激活时间（unix 秒）
        data.put(s.convertKey("gear"), 1);                                   // 档位：1=P档（M4D/M3D/M30 共用）
        data.put(s.convertKey("height_limit"), 120);                         // 飞行器限高（米）
        data.put(s.convertKey("home_distance"), 0.0);                        // 距 Home 点距离
        // distance_limit_status + rth_altitude（M30/M3D/M4D 共有，pushMode=0, rw）
        data.put(s.convertKey("distance_limit_status"), buildDistanceLimitStatus(s));
        data.put(s.convertKey("rth_altitude"), 100);                         // 返航高度（米）
        // is_near_area_limit / is_near_height_limit（M30/M3D/M4D 共有，pushMode=0, r）
        data.put(s.convertKey("is_near_area_limit"), 0);    // 0=未达到限飞区
        data.put(s.convertKey("is_near_height_limit"), 0);  // 0=未达到设定的限制高度
        data.put(s.convertKey("maintain_status"), buildDroneMaintainStatus(s));  // 保养信息（3 种类型）
        data.put(s.convertKey("night_lights_state"), state.isNightLightsState() ? 1 : 0);  // 夜航灯
        data.put(s.convertKey("obstacle_avoidance"), buildObstacleAvoidance(s));  // 避障状态
        data.put(s.convertKey("storage"), buildDroneStorage(s));             // 存储容量
        data.put(s.convertKey("total_flight_distance"), 0.0);                // 累计飞行总里程（米）
        data.put(s.convertKey("total_flight_sorties"), 0);                   // 累计飞行总架次
        data.put(s.convertKey("track_id"), "");                              // 轨迹ID（文档未明确，按真机示例）
        // 机型特有字段（如 cameras 数组、负载属性等，由子类追加）
        // 注：payloads（pushMode=1）不在 OSD，由 DockOnlineService.publishDroneState() 在 state topic 上报
        appendDroneSpecific(ctx, data);
        return data;
    }

    /**
     * 子类实现，追加该机型特有的 OSD 字段（如 cameras 数组、wireless_link_topo 等）。
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
        m.put(s.convertKey("return_home_power"), 30);
        m.put(s.convertKey("landing_power"), 15);
        m.put(s.convertKey("batteries"), List.of(
                buildBatteryCell(s, state, 0, "BAT0000000001"),
                buildBatteryCell(s, state, 1, "BAT0000000002")
        ));
        return m;
    }

    private Map<String, Object> buildBatteryCell(OsdStrategy s, DeviceState state, int index, String sn) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put(s.convertKey("capacity_percent"), state.getBatteryPercent());
        cell.put(s.convertKey("index"), index);
        cell.put(s.convertKey("sn"), sn);
        cell.put(s.convertKey("type"), 0);
        cell.put(s.convertKey("sub_type"), 0);
        cell.put(s.convertKey("firmware_version"), "1.2.3");
        cell.put(s.convertKey("loop_times"), 12);
        cell.put(s.convertKey("voltage"), state.getBatteryVoltage());
        cell.put(s.convertKey("temperature"), state.getBatteryTemperature());
        cell.put(s.convertKey("high_voltage_storage_days"), 0);
        return cell;
    }

    protected Map<String, Object> buildPositionState(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        DeviceState state = ctx.getState();
        // 字段对齐 DJI M3D properties 文档 position_state 结构：
        // is_fixed(是否收敛) / quality(搜星档位) / gps_number / rtk_number
        // 注：M3D 文档无 is_calibration 字段，已移除
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("is_fixed"), state.getPositionState());  // 0=未开始,1=收敛中,2=收敛成功,3=收敛失败
        m.put(s.convertKey("quality"), 5);  // 5=5档（文档枚举 {1,2,3,4,5,10}）
        m.put(s.convertKey("gps_number"), 18);
        m.put(s.convertKey("rtk_number"), 6);
        return m;
    }

    /**
     * 构造 distance_limit_status（飞行器限远状态，pushMode=0, rw）。
     * <p>M30/M3D/M4D 共有字段，子结构：state/distance_limit/is_near_distance_limit。</p>
     */
    protected Map<String, Object> buildDistanceLimitStatus(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("state"), 1);                  // 已设置
        m.put(s.convertKey("distance_limit"), 8000);      // 限远距离（米）
        m.put(s.convertKey("is_near_distance_limit"), 0); // 未接近
        return m;
    }

    /**
     * 构造 cameras 数组（飞行器相机信息，pushMode=0, r）。
     * <p>M30/M3D/M4D 共有字段，子字段全部对齐 DJI properties 文档。</p>
     * <p>红外相关字段（ir_zoom_factor/ir_metering_*）按 {@link OsdContext#isThermal()} 条件上报。</p>
     */
    protected List<Map<String, Object>> buildCameras(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        PayloadType camera = PayloadType.defaultCameraFor(ctx.getDroneType());
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> cam = new LinkedHashMap<>();
        // 基本字段
        cam.put(s.convertKey("payload_index"), camera != null ? camera.cameraIndex() : "52-0-0");
        cam.put(s.convertKey("camera_mode"), 0);           // 拍照
        cam.put(s.convertKey("photo_state"), 0);            // 空闲
        cam.put(s.convertKey("recording_state"), 0);        // 空闲
        cam.put(s.convertKey("zoom_factor"), 2.0);
        cam.put(s.convertKey("remain_photo_num"), 1000);
        cam.put(s.convertKey("remain_record_duration"), 3600);
        cam.put(s.convertKey("record_time"), 0);
        cam.put(s.convertKey("screen_split_enable"), 0);
        // liveview_world_region — 视场角在 liveview 中的区域
        Map<String, Object> liveviewRegion = new LinkedHashMap<>();
        liveviewRegion.put(s.convertKey("left"), 0.0);
        liveviewRegion.put(s.convertKey("top"), 0.0);
        liveviewRegion.put(s.convertKey("right"), 1.0);
        liveviewRegion.put(s.convertKey("bottom"), 1.0);
        cam.put(s.convertKey("liveview_world_region"), liveviewRegion);
        // 照片/视频存储设置
        cam.put(s.convertKey("photo_storage_settings"), List.of("current", "wide", "zoom"));
        cam.put(s.convertKey("video_storage_settings"), List.of("current", "wide", "zoom"));
        // 广角镜头曝光参数
        cam.put(s.convertKey("wide_exposure_mode"), 1);    // 自动
        cam.put(s.convertKey("wide_iso"), 0);              // Auto
        cam.put(s.convertKey("wide_shutter_speed"), 65534); // Auto
        cam.put(s.convertKey("wide_exposure_value"), 16);  // 0EV
        // 变焦镜头曝光参数
        cam.put(s.convertKey("zoom_exposure_mode"), 1);    // 自动
        cam.put(s.convertKey("zoom_iso"), 0);              // Auto
        cam.put(s.convertKey("zoom_shutter_speed"), 65534); // Auto
        cam.put(s.convertKey("zoom_exposure_value"), 16);  // 0EV
        // 变焦镜头对焦参数
        cam.put(s.convertKey("zoom_focus_mode"), 2);       // AFC
        cam.put(s.convertKey("zoom_focus_value"), 0);
        cam.put(s.convertKey("zoom_max_focus_value"), 1000);
        cam.put(s.convertKey("zoom_min_focus_value"), 0);
        cam.put(s.convertKey("zoom_calibrate_farthest_focus_value"), 1000);
        cam.put(s.convertKey("zoom_calibrate_nearest_focus_value"), 0);
        cam.put(s.convertKey("zoom_focus_state"), 0);      // 空闲
        // 红外相关字段（仅 thermal 机型上报）
        if (ctx.isThermal()) {
            cam.put(s.convertKey("ir_zoom_factor"), 2.0);
            cam.put(s.convertKey("ir_metering_mode"), 0);   // 关闭测温
            // ir_metering_point — 红外测温点
            Map<String, Object> irMeteringPoint = new LinkedHashMap<>();
            irMeteringPoint.put(s.convertKey("x"), 0.5);
            irMeteringPoint.put(s.convertKey("y"), 0.5);
            irMeteringPoint.put(s.convertKey("temperature"), 25.0);
            cam.put(s.convertKey("ir_metering_point"), irMeteringPoint);
            // ir_metering_area — 红外测温区域
            Map<String, Object> irMeteringArea = new LinkedHashMap<>();
            irMeteringArea.put(s.convertKey("x"), 0.0);
            irMeteringArea.put(s.convertKey("y"), 0.0);
            irMeteringArea.put(s.convertKey("width"), 1.0);
            irMeteringArea.put(s.convertKey("height"), 1.0);
            irMeteringArea.put(s.convertKey("aver_temperature"), 25.0);
            // min_temperature_point
            Map<String, Object> minTempPoint = new LinkedHashMap<>();
            minTempPoint.put(s.convertKey("x"), 0.0);
            minTempPoint.put(s.convertKey("y"), 0.0);
            minTempPoint.put(s.convertKey("temperature"), 20.0);
            irMeteringArea.put(s.convertKey("min_temperature_point"), minTempPoint);
            // max_temperature_point
            Map<String, Object> maxTempPoint = new LinkedHashMap<>();
            maxTempPoint.put(s.convertKey("x"), 1.0);
            maxTempPoint.put(s.convertKey("y"), 1.0);
            maxTempPoint.put(s.convertKey("temperature"), 30.0);
            irMeteringArea.put(s.convertKey("max_temperature_point"), maxTempPoint);
            cam.put(s.convertKey("ir_metering_area"), irMeteringArea);
        }
        list.add(cam);
        return list;
    }

    /**
     * 构造飞行器保养信息（pushMode=0, r）。
     * <p>飞行器保养分 3 种类型：1=基础保养, 2=常规保养, 3=深度保养（M4D 文档 maintain_status 结构）。
     * 与 Dock 的 maintain_status（1 条记录）不同，飞行器上报 3 条记录。</p>
     */
    protected Map<String, Object> buildDroneMaintainStatus(OsdStrategy s) {
        List<Map<String, Object>> array = new ArrayList<>();
        for (int type = 1; type <= 3; type++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(s.convertKey("state"), 0);                        // 0=无保养
            entry.put(s.convertKey("last_maintain_type"), type);         // 1=基础,2=常规,3=深度
            entry.put(s.convertKey("last_maintain_time"), 0);            // 上一次保养时间（秒）
            entry.put(s.convertKey("last_maintain_flight_time"), 0);     // 上一次保养时飞行航时（小时）
            entry.put(s.convertKey("last_maintain_flight_sorties"), 0);  // 上一次保养时飞行架次
            array.add(entry);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("maintain_status_array"), array);
        return m;
    }

    /**
     * 构造飞行器避障状态（pushMode=0, rw）。
     * <p>字段对齐 DJI M4D properties 文档 obstacle_avoidance 结构：horizon/upside/downside。</p>
     */
    protected Map<String, Object> buildObstacleAvoidance(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("horizon"), 1);   // 水平避障：1=开启
        m.put(s.convertKey("upside"), 1);    // 上视避障：1=开启
        m.put(s.convertKey("downside"), 1);  // 下视避障：1=开启
        return m;
    }

    /**
     * 构造飞行器存储容量（pushMode=0, r）。
     * <p>单位 KB，对齐 DJI M30 properties 文档 storage 结构。</p>
     */
    protected Map<String, Object> buildDroneStorage(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("total"), 1048576L);   // 总容量 1GB（KB）
        m.put(s.convertKey("used"), 524288L);      // 已使用 512MB（KB）
        return m;
    }

    /**
     * 构造负载属性（以负载索引为 key 的相机属性，M30 旧版方式）。
     * <p>M30 使用以负载索引为 key 的负载属性（如 "52-0-0"），M4D 使用 type_subtype_gimbalindex struct（升级方式）。</p>
     * <p>字段对齐 M30 properties 文档 {type-subtype-gimbalindex} 结构（pushMode=0）：
     * gimbal_pitch/roll/yaw → measure_target_* → zoom_factor → thermal_*（仅 thermal 机型）。</p>
     * <p>注：M30 文档中 {type-subtype-gimbalindex}.payload_index 的 pushMode=1（state topic），不在 OSD 中上报。
     * 这与 M3D/M4D 不同——M3D/M4D 文档中 type_subtype_gimbalindex.payload_index 的 pushMode=0（OSD），由 M4DDroneOsdBuilder.buildGimbalInfo() 上报。</p>
     * <p>注：version 字段文档中不存在，已移除。</p>
     * <p>模拟器不模拟测距场景，measure_target_error_state=3（NO_SIGNAL），其余 measure_target_* 为 0。</p>
     */
    protected Map<String, Object> buildPayload(PayloadType camera, OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        Map<String, Object> m = new LinkedHashMap<>();
        // 云台姿态字段（pushMode=0）
        m.put(s.convertKey("gimbal_pitch"), 0.0);
        m.put(s.convertKey("gimbal_roll"), 0.0);
        m.put(s.convertKey("gimbal_yaw"), 0.0);
        // 激光测距目标字段（pushMode=0，模拟器不模拟测距，error_state=3=NO_SIGNAL）
        m.put(s.convertKey("measure_target_longitude"), 0.0);
        m.put(s.convertKey("measure_target_latitude"), 0.0);
        m.put(s.convertKey("measure_target_altitude"), 0.0);
        m.put(s.convertKey("measure_target_distance"), 0.0);
        m.put(s.convertKey("measure_target_error_state"), 3);    // 3=NO_SIGNAL
        // 变焦倍数（pushMode=0）
        m.put(s.convertKey("zoom_factor"), 2.0);
        // 红外测温字段（仅 thermal 机型上报，pushMode=0, rw）
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
