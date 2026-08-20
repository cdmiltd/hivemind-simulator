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
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞行器 OSD Builder 抽象基类，采用模板方法模式。
 * <p>提供所有机型共用的基础飞行字段（位置、姿态、速度、电池、定位、负载标识），
 * 子类通过 {@link #appendDroneSpecific(OsdContext, Map)} 追加机型特有字段（如 cameras 数组、wireless_link_topo 等）。</p>
 * <p>红外字段（thermal_*）由子类通过 {@link OsdContext#isThermal()} 条件判断是否追加。</p>
 * <p>顶层 OSD 字段名引用 {@link OsdField} 枚举，嵌套结构内部字段直接使用 snake_case 字符串字面量。</p>
 */
public abstract class AbstractDroneOsdBuilder implements DroneOsdBuilder {

    @Override
    public final Map<String, Object> buildDroneOsd(OsdContext ctx) {
        DeviceState state = ctx.getState();
        Map<String, Object> data = new LinkedHashMap<>();
        // 共用基础飞行字段（所有机型都上报）
        // 字段对齐 DJI M30/M3D/M4D/M400 properties 文档：height=绝对高度(椭球面)，elevation=相对起飞点高度
        data.put(OsdField.MODE_CODE.fieldName(), state.getDroneModeCode());
        data.put(OsdField.LATITUDE.fieldName(), state.getDroneLatitude());
        data.put(OsdField.LONGITUDE.fieldName(), state.getDroneLongitude());
        data.put(OsdField.HEIGHT.fieldName(), state.getDroneElevation());
        data.put(OsdField.ELEVATION.fieldName(), state.getDroneHeight());
        data.put(OsdField.ATTITUDE_PITCH.fieldName(), state.getAttitudePitch());
        data.put(OsdField.ATTITUDE_ROLL.fieldName(), state.getAttitudeRoll());
        data.put(OsdField.ATTITUDE_HEAD.fieldName(), (int) state.getAttitudeYaw());  // int（M30/M3D/M4D 文档均为 int）
        data.put(OsdField.HORIZONTAL_SPEED.fieldName(), state.getHorizontalSpeed());
        data.put(OsdField.VERTICAL_SPEED.fieldName(), state.getVerticalSpeed());
        data.put(OsdField.WIND_SPEED.fieldName(), state.getWindSpeed() * 10);  // DJI 文档单位 0.1 m/s，上报值 ×10 转换
        data.put(OsdField.WIND_DIRECTION.fieldName(), state.getWindDirection());
        data.put(OsdField.BATTERY.fieldName(), buildBattery(ctx));
        data.put(OsdField.POSITION_STATE.fieldName(), buildPositionState(ctx));
        data.put(OsdField.TOTAL_FLIGHT_TIME.fieldName(), (float) state.getFlightTimeSeconds());  // float（M30/M3D/M4D 文档均为 float）
        // TC-BUILDER-014：补齐飞行器 OSD 共用字段（对齐 DJI M4D/M30 文档 + 真机示例）
        data.put(OsdField.ACTIVATION_TIME.fieldName(), 1700000000);              // 飞行器激活时间（unix 秒）
        // firmware_version — 飞行器固件版本（M400 Pilot pushMode=0 在 OSD 上报，M30/M3D/M4D pushMode=1 在 state topic 上报）
        if (includeFirmwareVersionInOsd()) {
            data.put(OsdField.FIRMWARE_VERSION.fieldName(), "0.0.0.0");
        }
        data.put(OsdField.GEAR.fieldName(), 1);                                   // 档位：1=P档（M4D/M3D/M30/M400 共用）
        data.put(OsdField.HEIGHT_LIMIT.fieldName(), 120);                         // 飞行器限高（米）
        data.put(OsdField.HOME_DISTANCE.fieldName(), 0.0);                        // 距 Home 点距离
        // distance_limit_status + rth_altitude（M30/M3D/M4D 共有，pushMode=0, rw；M400 Pilot 属性列表未列）
        if (includeDistanceLimitFields()) {
            data.put(OsdField.DISTANCE_LIMIT_STATUS.fieldName(), buildDistanceLimitStatus());
            data.put(OsdField.RTH_ALTITUDE.fieldName(), 100);                     // 返航高度（米）
        }
        // is_near_area_limit / is_near_height_limit（M30/M3D/M4D 共有，pushMode=0, r）
        data.put(OsdField.IS_NEAR_AREA_LIMIT.fieldName(), 0);    // 0=未达到限飞区
        data.put(OsdField.IS_NEAR_HEIGHT_LIMIT.fieldName(), 0);  // 0=未达到设定的限制高度
        data.put(OsdField.MAINTAIN_STATUS.fieldName(), buildDroneMaintainStatus());  // 保养信息（3 种类型）
        data.put(OsdField.NIGHT_LIGHTS_STATE.fieldName(), state.isNightLightsState() ? 1 : 0);  // 夜航灯
        data.put(OsdField.OBSTACLE_AVOIDANCE.fieldName(), buildObstacleAvoidance());  // 避障状态
        data.put(OsdField.STORAGE.fieldName(), buildDroneStorage());             // 存储容量
        data.put(OsdField.TOTAL_FLIGHT_DISTANCE.fieldName(), 0.0);                // 累计飞行总里程（米）
        data.put(OsdField.TOTAL_FLIGHT_SORTIES.fieldName(), 0);                   // 累计飞行总架次
        data.put(OsdField.TRACK_ID.fieldName(), "");                              // 轨迹ID（文档未明确，按真机示例）
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

    /**
     * 是否上报限远/返航高度字段（distance_limit_status/rth_altitude）。
     * <p>M30/M3D/M4D 属性列表包含这些字段，默认上报。M400 Pilot 属性列表未列，覆盖为 false。</p>
     * <p>mode_code/gear 不受此钩子控制，所有机型始终上报（pushMode=0 基础飞行字段）。</p>
     */
    protected boolean includeDistanceLimitFields() { return true; }

    /**
     * 是否在 OSD 上报 firmware_version。
     * <p>M400 Pilot 模式 firmware_version pushMode=0（OSD），其他机型 pushMode=1（state topic）。</p>
     */
    protected boolean includeFirmwareVersionInOsd() { return false; }

    // ==================== 共用子结构构造 ====================

    protected Map<String, Object> buildBattery(OsdContext ctx) {
        DeviceState state = ctx.getState();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capacity_percent", state.getBatteryPercent());
        m.put("remain_flight_time", Math.max(0, state.getBatteryPercent() * 30L / 100));
        m.put("return_home_power", 30);
        m.put("landing_power", 15);
        m.put("batteries", List.of(
                buildBatteryCell(state, 0, "BAT0000000001"),
                buildBatteryCell(state, 1, "BAT0000000002")
        ));
        return m;
    }

    private Map<String, Object> buildBatteryCell(DeviceState state, int index, String sn) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("capacity_percent", state.getBatteryPercent());
        cell.put("index", index);
        cell.put("sn", sn);
        cell.put("type", 0);
        cell.put("sub_type", 0);
        cell.put("firmware_version", "1.2.3");
        cell.put("loop_times", 12);
        cell.put("voltage", state.getBatteryVoltage());
        cell.put("temperature", state.getBatteryTemperature());
        cell.put("high_voltage_storage_days", 0);
        return cell;
    }

    protected Map<String, Object> buildPositionState(OsdContext ctx) {
        DeviceState state = ctx.getState();
        // 字段对齐 DJI M3D properties 文档 position_state 结构：
        // is_fixed(是否收敛) / quality(搜星档位) / gps_number / rtk_number
        // 注：M3D 文档无 is_calibration 字段，已移除
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("is_fixed", state.getPositionState());  // 0=未开始,1=收敛中,2=收敛成功,3=收敛失败
        m.put("quality", 5);  // 5=5档（文档枚举 {1,2,3,4,5,10}）
        m.put("gps_number", 18);
        m.put("rtk_number", 6);
        return m;
    }

    /**
     * 构造 distance_limit_status（飞行器限远状态，pushMode=0, rw）。
     * <p>M30/M3D/M4D 共有字段，子结构：state/distance_limit/is_near_distance_limit。</p>
     */
    protected Map<String, Object> buildDistanceLimitStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", 1);                  // 已设置
        m.put("distance_limit", 8000);      // 限远距离（米）
        m.put("is_near_distance_limit", 0); // 未接近
        return m;
    }

    /**
     * 构造 cameras 数组（飞行器相机信息，pushMode=0, r）。
     * <p>M30/M3D/M4D 共有字段，子字段全部对齐 DJI properties 文档。</p>
     * <p>红外相关字段（ir_zoom_factor/ir_metering_*）按 {@link OsdContext#isThermal()} 条件上报。</p>
     */
    protected List<Map<String, Object>> buildCameras(OsdContext ctx) {
        PayloadType camera = ctx.getSelectedPayload();
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> cam = new LinkedHashMap<>();
        // 基本字段
        cam.put("payload_index", camera != null ? camera.cameraIndex() : "52-0-0");
        cam.put("camera_mode", 0);           // 拍照
        cam.put("photo_state", 0);            // 空闲
        cam.put("recording_state", 0);        // 空闲
        cam.put("zoom_factor", 2.0);
        cam.put("remain_photo_num", 1000);
        cam.put("remain_record_duration", 3600);
        cam.put("record_time", 0);
        cam.put("screen_split_enable", 0);
        // liveview_world_region — 视场角在 liveview 中的区域
        Map<String, Object> liveviewRegion = new LinkedHashMap<>();
        liveviewRegion.put("left", 0.0);
        liveviewRegion.put("top", 0.0);
        liveviewRegion.put("right", 1.0);
        liveviewRegion.put("bottom", 1.0);
        cam.put("liveview_world_region", liveviewRegion);
        // 照片/视频存储设置
        cam.put("photo_storage_settings", List.of("current", "wide", "zoom"));
        cam.put("video_storage_settings", List.of("current", "wide", "zoom"));
        // 广角镜头曝光参数
        cam.put("wide_exposure_mode", 1);    // 自动
        cam.put("wide_iso", 0);              // Auto
        cam.put("wide_shutter_speed", 65534); // Auto
        cam.put("wide_exposure_value", 16);  // 0EV
        // 变焦镜头曝光参数
        cam.put("zoom_exposure_mode", 1);    // 自动
        cam.put("zoom_iso", 0);              // Auto
        cam.put("zoom_shutter_speed", 65534); // Auto
        cam.put("zoom_exposure_value", 16);  // 0EV
        // 变焦镜头对焦参数
        cam.put("zoom_focus_mode", 2);       // AFC
        cam.put("zoom_focus_value", 0);
        cam.put("zoom_max_focus_value", 1000);
        cam.put("zoom_min_focus_value", 0);
        cam.put("zoom_calibrate_farthest_focus_value", 1000);
        cam.put("zoom_calibrate_nearest_focus_value", 0);
        cam.put("zoom_focus_state", 0);      // 空闲
        // 红外相关字段（仅 thermal 机型上报）
        if (ctx.isThermal()) {
            cam.put("ir_zoom_factor", 2.0);
            cam.put("ir_metering_mode", 0);   // 关闭测温
            // ir_metering_point — 红外测温点
            Map<String, Object> irMeteringPoint = new LinkedHashMap<>();
            irMeteringPoint.put("x", 0.5);
            irMeteringPoint.put("y", 0.5);
            irMeteringPoint.put("temperature", 25.0);
            cam.put("ir_metering_point", irMeteringPoint);
            // ir_metering_area — 红外测温区域
            Map<String, Object> irMeteringArea = new LinkedHashMap<>();
            irMeteringArea.put("x", 0.0);
            irMeteringArea.put("y", 0.0);
            irMeteringArea.put("width", 1.0);
            irMeteringArea.put("height", 1.0);
            irMeteringArea.put("aver_temperature", 25.0);
            // min_temperature_point
            Map<String, Object> minTempPoint = new LinkedHashMap<>();
            minTempPoint.put("x", 0.0);
            minTempPoint.put("y", 0.0);
            minTempPoint.put("temperature", 20.0);
            irMeteringArea.put("min_temperature_point", minTempPoint);
            // max_temperature_point
            Map<String, Object> maxTempPoint = new LinkedHashMap<>();
            maxTempPoint.put("x", 1.0);
            maxTempPoint.put("y", 1.0);
            maxTempPoint.put("temperature", 30.0);
            irMeteringArea.put("max_temperature_point", maxTempPoint);
            cam.put("ir_metering_area", irMeteringArea);
        }
        list.add(cam);
        return list;
    }

    /**
     * 构造飞行器保养信息（pushMode=0, r）。
     * <p>飞行器保养分 3 种类型：1=基础保养, 2=常规保养, 3=深度保养（M4D 文档 maintain_status 结构）。
     * 与 Dock 的 maintain_status（1 条记录）不同，飞行器上报 3 条记录。</p>
     */
    protected Map<String, Object> buildDroneMaintainStatus() {
        List<Map<String, Object>> array = new ArrayList<>();
        for (int type = 1; type <= 3; type++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("state", 0);                        // 0=无保养
            entry.put("last_maintain_type", type);         // 1=基础,2=常规,3=深度
            entry.put("last_maintain_time", 0);            // 上一次保养时间（秒）
            entry.put("last_maintain_flight_time", 0);     // 上一次保养时飞行航时（小时）
            entry.put("last_maintain_flight_sorties", 0);  // 上一次保养时飞行架次
            array.add(entry);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maintain_status_array", array);
        return m;
    }

    /**
     * 构造飞行器避障状态（pushMode=0, rw）。
     * <p>字段对齐 DJI M4D properties 文档 obstacle_avoidance 结构：horizon/upside/downside。</p>
     */
    protected Map<String, Object> buildObstacleAvoidance() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("horizon", 1);   // 水平避障：1=开启
        m.put("upside", 1);    // 上视避障：1=开启
        m.put("downside", 1);  // 下视避障：1=开启
        return m;
    }

    /**
     * 构造飞行器存储容量（pushMode=0, r）。
     * <p>单位 KB，对齐 DJI M30 properties 文档 storage 结构。</p>
     */
    protected Map<String, Object> buildDroneStorage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", 1048576L);   // 总容量 1GB（KB）
        m.put("used", 524288L);      // 已使用 512MB（KB）
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
        Map<String, Object> m = new LinkedHashMap<>();
        // 云台姿态字段（pushMode=0）
        m.put("gimbal_pitch", 0.0);
        m.put("gimbal_roll", 0.0);
        m.put("gimbal_yaw", 0.0);
        // 激光测距目标字段（pushMode=0，模拟器不模拟测距，error_state=3=NO_SIGNAL）
        m.put("measure_target_longitude", 0.0);
        m.put("measure_target_latitude", 0.0);
        m.put("measure_target_altitude", 0.0);
        m.put("measure_target_distance", 0.0);
        m.put("measure_target_error_state", 3);    // 3=NO_SIGNAL
        // 变焦倍数（pushMode=0）
        m.put("zoom_factor", 2.0);
        // 红外测温字段（仅 thermal 机型上报，pushMode=0, rw）
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
