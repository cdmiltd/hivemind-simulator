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
import java.util.List;
import java.util.Map;

/**
 * 机场 OSD Builder 抽象基类，采用模板方法模式。
 * <p>提供所有 Dock 版本共用的字段（位置、网络、存储、子设备、机械结构与环境监测），
 * 子类通过 {@link #appendDockSpecific(OsdContext, Map, Map, Map)} 追加版本特有字段。</p>
 * <p>字段命名经 {@link OsdContext#getStrategy()} 的 {@link OsdStrategy#convertKey(String)} 转换，
 * 实现"字段集"与"命名风格"两个维度解耦。</p>
 * <p>对齐 DJI 文档「机场的设备属性推送是分多条推送的」（Dock3 properties 文档「设备属性推送」章节）：
 * {@link #buildDockOsd(OsdContext)}
 * 返回 3 条 OSD data（电源/电池/保养/统计、任务/图传/媒体、位置/环境/机械/子设备），
 * 由 {@link DeviceSimulator} 分别包装 envelope 发布到 osd topic。</p>
 * <p>注：{@code live_capacity} 的 pushMode=1，应在 state topic 上报，不在 OSD 中，
 * 由 {@code DockOnlineService.publishLiveCapacity()} 通过 state topic 上报。</p>
 */
public abstract class AbstractDockOsdBuilder implements DockOsdBuilder {

    @Override
    public final List<Map<String, Object>> buildDockOsd(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        DeviceState state = ctx.getState();

        // Group 1: 电源/电池/保养/统计
        Map<String, Object> powerAndBattery = new LinkedHashMap<>();
        powerAndBattery.put(s.convertKey("job_number"), 0);                     // 机场累计作业次数
        powerAndBattery.put(s.convertKey("activation_time"), 1700000000);       // 机场激活时间（s）
        powerAndBattery.put(s.convertKey("working_current"), 1000.0);           // 工作电流（mA）
        powerAndBattery.put(s.convertKey("working_voltage"), 12000);            // 工作电压（mV）
        powerAndBattery.put(s.convertKey("electric_supply_voltage"), state.getElectricSupplyVoltage()); // 市电电压（V）— OSD 封面字段
        powerAndBattery.put(s.convertKey("backup_battery"), buildBackupBattery(ctx));
        powerAndBattery.put(s.convertKey("drone_battery_maintenance_info"), buildDroneBatteryMaintenanceInfo(s));
        powerAndBattery.put(s.convertKey("maintain_status"), buildMaintainStatus(s));

        // Group 2: 任务/图传/媒体
        Map<String, Object> taskAndLink = new LinkedHashMap<>();
        taskAndLink.put(s.convertKey("flighttask_step_code"), 5);               // 任务空闲
        taskAndLink.put(s.convertKey("media_file_detail"), buildMediaFileDetail(s));
        taskAndLink.put(s.convertKey("wireless_link"), buildWirelessLink(s));
        taskAndLink.put(s.convertKey("drc_state"), state.getDrcState());        // DRC 链路状态

        // Group 3: 位置/环境/机械/子设备
        Map<String, Object> positionAndEnv = new LinkedHashMap<>();
        positionAndEnv.put(s.convertKey("mode_code"), state.getDockModeCode());
        positionAndEnv.put(s.convertKey("latitude"), ctx.getProps().location().latitude());
        positionAndEnv.put(s.convertKey("longitude"), ctx.getProps().location().longitude());
        positionAndEnv.put(s.convertKey("height"), ctx.getProps().location().height());
        positionAndEnv.put(s.convertKey("network_state"), buildNetworkState(s));
        positionAndEnv.put(s.convertKey("storage"), buildStorage(s));
        positionAndEnv.put(s.convertKey("sub_device"), buildSubDevice(ctx));
        positionAndEnv.put(s.convertKey("cover_state"), state.isCoverOpen() ? 1 : 0);
        positionAndEnv.put(s.convertKey("drone_in_dock"), state.isDroneInDock() ? 1 : 0);
        positionAndEnv.put(s.convertKey("drone_charge_state"), buildDroneChargeState(s, state));
        positionAndEnv.put(s.convertKey("temperature"), state.getDockTemperature());
        positionAndEnv.put(s.convertKey("humidity"), state.getDockHumidity());
        positionAndEnv.put(s.convertKey("wind_speed"), state.getWindSpeed());
        positionAndEnv.put(s.convertKey("rainfall"), state.getRainfall());
        positionAndEnv.put(s.convertKey("environment_temperature"), 25.0);      // 环境温度（°C）
        positionAndEnv.put(s.convertKey("supplement_light_state"), 0);
        positionAndEnv.put(s.convertKey("air_conditioner"), buildAirConditioner(s)); // 机场空调（struct，Dock1 OSD 示例误用 air_conditioner_mode 标量，以属性列表为准）
        positionAndEnv.put(s.convertKey("emergency_stop_state"), 0);               // 紧急停止按钮状态紧急停止按钮关闭
        positionAndEnv.put(s.convertKey("alarm_state"), 0);                     // 机场声光报警关闭
        positionAndEnv.put(s.convertKey("putter_state"), state.isPutterExpanded() ? 1 : 0); // 推杆状态
        positionAndEnv.put(s.convertKey("battery_store_mode"), 2);              // 待命模式
        positionAndEnv.put(s.convertKey("alternate_land_point"), buildAlternateLandPoint(s));
        positionAndEnv.put(s.convertKey("first_power_on"), 1700000000000L);     // 首次上电时间（ms）
        positionAndEnv.put(s.convertKey("position_state"), buildDockPositionState(s));

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

    protected Map<String, Object> buildNetworkState(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("type"), 1);
        m.put(s.convertKey("quality"), 5);
        m.put(s.convertKey("rate"), 4096);
        return m;
    }

    protected Map<String, Object> buildStorage(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("total"), 1048576L);
        m.put(s.convertKey("used"), 524288L);
        return m;
    }

    /**
     * 构造备用电池信息（三版共有字段）。
     * <p>DJI spec 字段顺序: switch / voltage / temperature</p>
     */
    protected Map<String, Object> buildBackupBattery(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("switch"), 1);
        m.put(s.convertKey("voltage"), 9500);
        m.put(s.convertKey("temperature"), ctx.getState().getBackupBatteryTemperature());
        return m;
    }

    /**
     * 构造飞行器充电状态（DJI 文档定义为 struct：capacity_percent + state）。
     */
    protected Map<String, Object> buildDroneChargeState(OsdStrategy s, DeviceState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("capacity_percent"), state.getBatteryPercent());
        m.put(s.convertKey("state"), state.isDroneInDock() ? 1 : 0); // 0=空闲, 1=充电中
        return m;
    }

    /**
     * 构造机场空调工作状态信息（三版共有 struct，Dock1 OSD 示例误用 air_conditioner_mode 标量，以属性列表为准，待真机验证）。
     */
    protected Map<String, Object> buildAirConditioner(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("air_conditioner_state"), 0); // 空闲模式
        m.put(s.convertKey("switch_time"), 0);           // 剩余等待可切换时间
        return m;
    }

    /**
     * 构造媒体文件上传细节（pushMode=0, r）。
     */
    protected Map<String, Object> buildMediaFileDetail(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("remain_upload"), 0); // 待上传数量
        return m;
    }

    /**
     * 构造机场搜星状态（pushMode=0, r）。
     * <p>字段对齐 DJI properties 文档 position_state 结构。</p>
     */
    protected Map<String, Object> buildDockPositionState(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("is_calibration"), 1);  // 已标定
        m.put(s.convertKey("is_fixed"), 2);         // 收敛成功
        m.put(s.convertKey("quality"), 10);         // RTK fixed
        m.put(s.convertKey("gps_number"), 20);
        m.put(s.convertKey("rtk_number"), 15);
        return m;
    }

    /**
     * 构造图传链路（pushMode=0, r）。
     */
    protected Map<String, Object> buildWirelessLink(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("dongle_number"), 1);      // 飞行器上 Dongle 数量
        m.put(s.convertKey("4g_link_state"), 1);       // 4G 链路连接
        m.put(s.convertKey("sdr_link_state"), 1);      // SDR 链路连接
        m.put(s.convertKey("link_workmode"), 1);       // 4G 融合模式
        m.put(s.convertKey("sdr_quality"), 5);         // SDR 信号质量
        m.put(s.convertKey("4g_quality"), 5);          // 总体 4G 信号质量
        m.put(s.convertKey("4g_uav_quality"), 5);      // 天端 4G 信号质量
        m.put(s.convertKey("4g_gnd_quality"), 5);      // 地端 4G 信号质量
        m.put(s.convertKey("sdr_freq_band"), 2.4);     // SDR 频段
        m.put(s.convertKey("4g_freq_band"), 1.8);      // 4G 频段
        return m;
    }

    /**
     * 构造备降点（pushMode=0, r）。
     */
    protected Map<String, Object> buildAlternateLandPoint(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("longitude"), 0.0);
        m.put(s.convertKey("latitude"), 0.0);
        m.put(s.convertKey("safe_land_height"), 0.0);
        m.put(s.convertKey("is_configured"), 0);       // 未设置备降点
        m.put(s.convertKey("height"), 0.0);
        return m;
    }

    /**
     * 构造飞行器电池保养信息（pushMode=0, r）。
     */
    protected Map<String, Object> buildDroneBatteryMaintenanceInfo(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("maintenance_state"), 0);       // 无需保养
        m.put(s.convertKey("maintenance_time_left"), 0);   // 电池保养剩余时间（h）
        m.put(s.convertKey("heat_state"), 0);               // 电池未开启加热或保温
        m.put(s.convertKey("batteries"), List.of());        // 电池详细信息（舱内关机时上报）
        return m;
    }

    /**
     * 构造保养信息（pushMode=0, r）。
     */
    protected Map<String, Object> buildMaintainStatus(OsdStrategy s) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(s.convertKey("state"), 0);                        // 无保养
        entry.put(s.convertKey("last_maintain_type"), 0);           // 无保养
        entry.put(s.convertKey("last_maintain_time"), 0);           // 上一次保养时间
        entry.put(s.convertKey("last_maintain_work_sorties"), 0);   // 上一次保养时作业架次

        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("maintain_status_array"), List.of(entry));
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
        OsdStrategy s = ctx.getStrategy();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("device_sn"), ctx.getRuntimeConfig().getDroneSn());
        m.put(s.convertKey(subDeviceModelKeyField()), ctx.getRuntimeConfig().getDroneType().modelKey());
        m.put(s.convertKey("device_online_status"), ctx.getState().isOnline() ? 1 : 0);
        m.put(s.convertKey("device_paired"), 1);
        return m;
    }

}
