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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机场 OSD Builder 抽象基类，采用模板方法模式。
 * <p>提供所有 Dock 版本共用的字段（位置、网络、存储、子设备、机械结构与环境监测），
 * 子类通过 {@link #appendDockSpecific(OsdContext, Map, Map, Map)} 追加版本特有字段。</p>
 * <p>顶层 OSD 字段名引用 {@link OsdField} 枚举，嵌套结构内部字段直接使用 snake_case 字符串字面量。</p>
 * <p>对齐 DJI 文档「机场的设备属性推送是分多条推送的」（Dock3 properties 文档「设备属性推送」章节）：
 * {@link #buildDockOsd(OsdContext)}
 * 返回 3 条 OSD data（电源/电池/保养/统计、任务/图传/媒体、位置/环境/机械/子设备），
 * 由 {@link DeviceSimulator} 分别包装 envelope 发布到 osd topic。</p>
 * <p>注：{@code live_capacity} 的 pushMode=1，应在 state topic 上报，不在 OSD 中，
 * 由 {@code DockOnlineService.publishDockState()} 合并到机场 state 消息中一次性上报。</p>
 */
public abstract class AbstractDockOsdBuilder implements DockOsdBuilder {

    @Override
    public final List<Map<String, Object>> buildDockOsd(OsdContext ctx) {
        DeviceState state = ctx.getState();

        // Group 1: 电源/电池/保养/统计
        Map<String, Object> powerAndBattery = new LinkedHashMap<>();
        powerAndBattery.put(OsdField.JOB_NUMBER.fieldName(), 0);                     // 机场累计作业次数
        powerAndBattery.put(OsdField.ACTIVATION_TIME.fieldName(), 1700000000);       // 机场激活时间（s）
        powerAndBattery.put(OsdField.WORKING_CURRENT.fieldName(), 1000.0);           // 工作电流（mA）
        powerAndBattery.put(OsdField.WORKING_VOLTAGE.fieldName(), 12000);            // 工作电压（mV）
        powerAndBattery.put(OsdField.BACKUP_BATTERY.fieldName(), buildBackupBattery(ctx));
        powerAndBattery.put(OsdField.DRONE_BATTERY_MAINTENANCE_INFO.fieldName(), buildDroneBatteryMaintenanceInfo());
        powerAndBattery.put(OsdField.MAINTAIN_STATUS.fieldName(), buildMaintainStatus());

        // Group 2: 任务/图传/媒体
        Map<String, Object> taskAndLink = new LinkedHashMap<>();
        taskAndLink.put(OsdField.FLIGHTTASK_STEP_CODE.fieldName(), 5);               // 任务空闲
        taskAndLink.put(OsdField.MEDIA_FILE_DETAIL.fieldName(), buildMediaFileDetail());
        taskAndLink.put(OsdField.WIRELESS_LINK.fieldName(), buildWirelessLink());
        taskAndLink.put(OsdField.DRC_STATE.fieldName(), state.getDrcState());        // DRC 链路状态

        // Group 3: 位置/环境/机械/子设备
        Map<String, Object> positionAndEnv = new LinkedHashMap<>();
        positionAndEnv.put(OsdField.MODE_CODE.fieldName(), state.getDockModeCode());
        positionAndEnv.put(OsdField.LATITUDE.fieldName(), ctx.getProps().location().latitude());
        positionAndEnv.put(OsdField.LONGITUDE.fieldName(), ctx.getProps().location().longitude());
        positionAndEnv.put(OsdField.HEIGHT.fieldName(), ctx.getProps().location().height());
        positionAndEnv.put(OsdField.NETWORK_STATE.fieldName(), buildNetworkState());
        positionAndEnv.put(OsdField.STORAGE.fieldName(), buildStorage());
        positionAndEnv.put(OsdField.SUB_DEVICE.fieldName(), buildSubDevice(ctx));
        positionAndEnv.put(OsdField.COVER_STATE.fieldName(), state.isCoverOpen() ? 1 : 0);
        positionAndEnv.put(OsdField.DRONE_IN_DOCK.fieldName(), state.isDroneInDock() ? 1 : 0);
        positionAndEnv.put(OsdField.DRONE_CHARGE_STATE.fieldName(), buildDroneChargeState(state));
        positionAndEnv.put(OsdField.TEMPERATURE.fieldName(), state.getDockTemperature());
        positionAndEnv.put(OsdField.HUMIDITY.fieldName(), state.getDockHumidity());
        positionAndEnv.put(OsdField.WIND_SPEED.fieldName(), state.getWindSpeed() * 10);  // DJI 文档单位 0.1 m/s，上报值 ×10 转换
        positionAndEnv.put(OsdField.RAINFALL.fieldName(), state.getRainfall());
        positionAndEnv.put(OsdField.ENVIRONMENT_TEMPERATURE.fieldName(), 25.0);      // 环境温度（°C）
        positionAndEnv.put(OsdField.SUPPLEMENT_LIGHT_STATE.fieldName(), 0);
        positionAndEnv.put(OsdField.AIR_CONDITIONER.fieldName(), buildAirConditioner()); // 机场空调（struct，Dock1 OSD 示例误用 air_conditioner_mode 标量，以属性列表为准）
        positionAndEnv.put(OsdField.EMERGENCY_STOP_STATE.fieldName(), 0);               // 紧急停止按钮状态紧急停止按钮关闭
        positionAndEnv.put(OsdField.ALARM_STATE.fieldName(), 0);                     // 机场声光报警关闭
        positionAndEnv.put(OsdField.BATTERY_STORE_MODE.fieldName(), 2);              // 待命模式
        positionAndEnv.put(OsdField.ALTERNATE_LAND_POINT.fieldName(), buildAlternateLandPoint());
        positionAndEnv.put(OsdField.FIRST_POWER_ON.fieldName(), 1700000000000L);     // 首次上电时间（ms）
        positionAndEnv.put(OsdField.POSITION_STATE.fieldName(), buildDockPositionState());

        // 版本特有字段
        appendDockSpecific(ctx, powerAndBattery, taskAndLink, positionAndEnv);

        return List.of(powerAndBattery, taskAndLink, positionAndEnv);
    }

    /**
     * 子类实现，追加该 Dock 版本特有的 OSD 字段到对应分组。
     *
     * @param ctx            OSD 上下文
     * @param powerAndBattery Group 1：电源/电池/保养/统计
     * @param taskAndLink     Group 2：任务/图传/媒体
     * @param positionAndEnv  Group 3：位置/环境/机械/子设备
     */
    protected abstract void appendDockSpecific(OsdContext ctx,
                                                Map<String, Object> powerAndBattery,
                                                Map<String, Object> taskAndLink,
                                                Map<String, Object> positionAndEnv);

    /**
     * 合并多条 OSD data 为单条（仅供测试辅助）。
     */
    public static Map<String, Object> mergeGroups(List<Map<String, Object>> groups) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map<String, Object> g : groups) {
            merged.putAll(g);
        }
        return merged;
    }

    // ==================== 共用子结构构造 ====================

    protected Map<String, Object> buildNetworkState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", 1);
        m.put("quality", 5);
        m.put("rate", 4096);
        return m;
    }

    protected Map<String, Object> buildStorage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", 1048576L);
        m.put("used", 524288L);
        return m;
    }

    /**
     * 构造备用电池信息（三版共有字段）。
     * <p>DJI spec 字段顺序: switch / voltage / temperature</p>
     */
    protected Map<String, Object> buildBackupBattery(OsdContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("switch", 1);
        m.put("voltage", 9500);
        m.put("temperature", ctx.getState().getBackupBatteryTemperature());
        return m;
    }

    /**
     * 构造飞行器充电状态（DJI 文档定义为 struct：capacity_percent + state）。
     */
    protected Map<String, Object> buildDroneChargeState(DeviceState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capacity_percent", state.getBatteryPercent());
        m.put("state", state.isDroneInDock() ? 1 : 0); // 0=空闲, 1=充电中
        return m;
    }

    /**
     * 构造机场空调工作状态信息（三版共有 struct，Dock1 OSD 示例误用 air_conditioner_mode 标量，以属性列表为准，待真机验证）。
     */
    protected Map<String, Object> buildAirConditioner() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("air_conditioner_state", 0); // 空闲模式
        m.put("switch_time", 0);           // 剩余等待可切换时间
        return m;
    }

    /**
     * 构造媒体文件上传细节（pushMode=0, r）。
     */
    protected Map<String, Object> buildMediaFileDetail() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("remain_upload", 0); // 待上传数量
        return m;
    }

    /**
     * 构造机场搜星状态（pushMode=0, r）。
     * <p>字段对齐 DJI properties 文档 position_state 结构。</p>
     */
    protected Map<String, Object> buildDockPositionState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("is_calibration", 1);  // 已标定
        m.put("is_fixed", 2);         // 收敛成功
        m.put("quality", 10);         // RTK fixed
        m.put("gps_number", 20);
        m.put("rtk_number", 15);
        return m;
    }

    /**
     * 构造图传链路（pushMode=0, r）。
     */
    protected Map<String, Object> buildWirelessLink() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dongle_number", 1);      // 飞行器上 Dongle 数量
        m.put("4g_link_state", 1);       // 4G 链路连接
        m.put("sdr_link_state", 1);      // SDR 链路连接
        m.put("link_workmode", 1);       // 4G 融合模式
        m.put("sdr_quality", 5);         // SDR 信号质量
        m.put("4g_quality", 5);          // 总体 4G 信号质量
        m.put("4g_uav_quality", 5);      // 天端 4G 信号质量
        m.put("4g_gnd_quality", 5);      // 地端 4G 信号质量
        m.put("sdr_freq_band", 2.4);     // SDR 频段
        m.put("4g_freq_band", 1.8);      // 4G 频段
        return m;
    }

    /**
     * 构造备降点（pushMode=0, r）。
     */
    protected Map<String, Object> buildAlternateLandPoint() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("longitude", 0.0);
        m.put("latitude", 0.0);
        m.put("safe_land_height", 0.0);
        m.put("is_configured", 0);       // 未设置备降点
        m.put("height", 0.0);
        return m;
    }

    /**
     * 构造飞行器电池保养信息（pushMode=0, r）。
     */
    protected Map<String, Object> buildDroneBatteryMaintenanceInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maintenance_state", 0);       // 无需保养
        m.put("maintenance_time_left", 0);   // 电池保养剩余时间（h）
        m.put("heat_state", 0);               // 电池未开启加热或保温
        m.put("batteries", List.of());        // 电池详细信息（舱内关机时上报）
        return m;
    }

    /**
     * 构造保养信息（pushMode=0, r）。
     */
    protected Map<String, Object> buildMaintainStatus() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("state", 0);                        // 无保养
        entry.put("last_maintain_type", 0);           // 无保养
        entry.put("last_maintain_time", 0);           // 上一次保养时间
        entry.put("last_maintain_work_sorties", 0);   // 上一次保养时作业架次

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maintain_status_array", List.of(entry));
        return m;
    }

    /**
     * 子设备枚举值字段名：Dock1 为 product_type，Dock2/Dock3 为 device_model_key。
     * <p>子类可覆盖以匹配对应 Dock 版本的 DJI 协议。</p>
     */
    protected String subDeviceModelKeyField() {
        return "device_model_key";
    }

    /**
     * 构造子设备信息。
     * <p>注意：字段名需转换，但其值（modelKey 字符串）不转换。</p>
     */
    protected Map<String, Object> buildSubDevice(OsdContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("device_sn", ctx.getRuntimeConfig().getDroneSn());
        m.put(subDeviceModelKeyField(), ctx.getRuntimeConfig().getDroneType().modelKey());
        m.put("device_online_status", ctx.getState().isOnline() ? 1 : 0);
        m.put("device_paired", 1);
        return m;
    }

}
