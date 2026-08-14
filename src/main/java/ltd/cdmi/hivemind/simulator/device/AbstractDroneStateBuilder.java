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
 * 飞行器 state Builder 抽象基类，提供共用字段构造方法。
 * <p>子类通过实现 {@link #buildDroneState} 追加机型特有字段，
 * 可调用本类的 {@link #buildDongleInfos} 和 {@link #buildCameraWatermarkSettings} 复用公共结构。</p>
 */
public abstract class AbstractDroneStateBuilder implements DroneStateBuilder {

    /**
     * 构造 4G Dongle 信息数组（dongle_infos）。
     * <p>结构与 PilotOnlineService.publishControllerState 中一致（DJI Cloud API 通用 Dongle 信息结构）。</p>
     * <p>模拟值：1 个支持 eSIM 的新 Dongle，已激活，使用 eSIM（移动运营商）。</p>
     * <p>核实依据：DJI Matrice 4 系列 / Mavic 3 行业系列设备属性列表 dongle_infos 结构（pushMode=1, r）。</p>
     */
    protected List<Map<String, Object>> buildDongleInfos() {
        List<Map<String, Object>> dongleInfos = new ArrayList<>();
        Map<String, Object> dongle = new LinkedHashMap<>();
        dongle.put("imei", "000000000000000");
        dongle.put("dongle_type", 10);        // 10=支持 eSIM 的新 Dongle
        dongle.put("eid", "00000000000000000000000000000000");
        dongle.put("esim_activate_state", 1); // 1=已激活
        dongle.put("sim_card_state", 1);      // 1=已插入
        dongle.put("sim_slot", 2);            // 2=eSIM
        // esim_infos — eSIM 信息数组
        List<Map<String, Object>> esimInfos = new ArrayList<>();
        Map<String, Object> esim = new LinkedHashMap<>();
        esim.put("telecom_operator", 1);      // 1=移动
        esim.put("enabled", true);
        esim.put("iccid", "0000000000000000000");
        esimInfos.add(esim);
        dongle.put("esim_infos", esimInfos);
        // sim_info — 实体 SIM 卡信息
        Map<String, Object> simInfo = new LinkedHashMap<>();
        simInfo.put("telecom_operator", 0);   // 0=未知
        simInfo.put("sim_type", 0);           // 0=未知
        simInfo.put("iccid", "");
        dongle.put("sim_info", simInfo);
        dongleInfos.add(dongle);
        return dongleInfos;
    }

    /**
     * 构造相机水印设置（camera_watermark_settings）。
     * <p>默认全部关闭，对齐 DJI 设备属性文档 camera_watermark_settings 结构（pushMode=1, rw）。</p>
     */
    protected Map<String, Object> buildCameraWatermarkSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("global_enable", 0);             // 0=关闭
        settings.put("drone_type_enable", 0);
        settings.put("drone_sn_enable", 0);
        settings.put("datetime_enable", 0);
        settings.put("gps_enable", 0);
        settings.put("user_custom_string_enable", 0);
        settings.put("user_custom_string", "");
        settings.put("layout", 0);                    // 0=左上
        return settings;
    }
}
