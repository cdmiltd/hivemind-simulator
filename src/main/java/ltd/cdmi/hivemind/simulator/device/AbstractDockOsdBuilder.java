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
 * 机场 OSD Builder 抽象基类，采用模板方法模式。
 * <p>提供所有 Dock 版本共用的字段（位置、网络、存储、子设备、直播能力、机械结构与环境监测），
 * 子类通过 {@link #appendDockSpecific(OsdContext, Map)} 追加版本特有字段。</p>
 * <p>字段命名经 {@link OsdContext#getStrategy()} 的 {@link OsdStrategy#convertKey(String)} 转换，
 * 实现"字段集"与"命名风格"两个维度解耦。</p>
 */
public abstract class AbstractDockOsdBuilder implements DockOsdBuilder {

    @Override
    public final Map<String, Object> buildDockOsd(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        DeviceState state = ctx.getState();
        Map<String, Object> data = new LinkedHashMap<>();
        // 基础共用字段（所有机场版本都上报）
        data.put(s.convertKey("mode_code"), state.getDockModeCode());
        data.put(s.convertKey("latitude"), ctx.getProps().location().latitude());
        data.put(s.convertKey("longitude"), ctx.getProps().location().longitude());
        data.put(s.convertKey("height"), ctx.getProps().location().height());
        data.put(s.convertKey("network_state"), buildNetworkState(s));
        data.put(s.convertKey("storage"), buildStorage(s));
        data.put(s.convertKey("sub_device"), buildSubDevice(ctx));
        data.put(s.convertKey("live_capacity"), buildLiveCapacity(ctx));
        // 三版共有机械结构与环境监测字段（Dock1/Dock2/Dock3 properties 均包含）
        data.put(s.convertKey("cover_state"), state.isCoverOpen() ? 1 : 0);
        data.put(s.convertKey("drone_in_dock"), state.isDroneInDock() ? 1 : 0);
        data.put(s.convertKey("drone_charge_state"), state.getDroneChargeState());
        data.put(s.convertKey("temperature"), state.getDockTemperature());
        data.put(s.convertKey("humidity"), state.getDockHumidity());
        data.put(s.convertKey("wind_speed"), state.getWindSpeed());
        data.put(s.convertKey("rainfall"), state.getRainfall());
        data.put(s.convertKey("backup_battery"), buildBackupBattery(ctx));
        // 三版共有控制字段（Dock1/Dock2/Dock3 properties 均包含）
        data.put(s.convertKey("air_conditioner"), buildAirConditioner(s));
        data.put(s.convertKey("supplement_light_state"), 0);
        data.put(s.convertKey("silent_mode"), state.getSilentMode());
        // 版本特有字段
        appendDockSpecific(ctx, data);
        return data;
    }

    /**
     * 子类实现，追加该 Dock 版本特有的 OSD 字段。
     *
     * @param ctx  OSD 上下文
     * @param data 已填充共用字段的 Map，子类直接往里 put 特有字段
     */
    protected abstract void appendDockSpecific(OsdContext ctx, Map<String, Object> data);

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
     * <p>DJI spec: switch / voltage / temperature</p>
     */
    protected Map<String, Object> buildBackupBattery(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("voltage"), 9500);
        m.put(s.convertKey("temperature"), ctx.getState().getBackupBatteryTemperature());
        m.put(s.convertKey("switch"), 1);
        return m;
    }

    /**
     * 构造机场空调工作状态信息（三版共有字段）。
     */
    protected Map<String, Object> buildAirConditioner(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("air_conditioner_state"), 0); // 空闲模式
        m.put(s.convertKey("switch_time"), 0);           // 剩余等待可切换时间
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

    /**
     * 构造直播能力信息，包含飞行器主相机与机场相机两路视频流。
     */
    protected Map<String, Object> buildLiveCapacity(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("available_video_number"), 2);
        List<Map<String, Object>> videoList = new ArrayList<>();
        // 飞行器主相机
        PayloadType camera = PayloadType.defaultCameraFor(ctx.getRuntimeConfig().getDroneType());
        if (camera != null) {
            Map<String, Object> cam = new LinkedHashMap<>();
            cam.put(s.convertKey("video_index"), camera.cameraIndex());
            cam.put(s.convertKey("video_type"), camera.getType());
            cam.put(s.convertKey("switchable"), 0);
            videoList.add(cam);
        }
        // 机场相机（舱外）
        Map<String, Object> dockCam = new LinkedHashMap<>();
        dockCam.put(s.convertKey("video_index"), PayloadType.DOCK_CAMERA.cameraIndex());
        dockCam.put(s.convertKey("video_type"), PayloadType.DOCK_CAMERA.getType());
        dockCam.put(s.convertKey("switchable"), 1);
        videoList.add(dockCam);
        m.put(s.convertKey("video_list"), videoList);
        return m;
    }
}
