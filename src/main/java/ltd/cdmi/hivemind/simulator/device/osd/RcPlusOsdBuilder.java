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

import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.dji.cloudapi.sdk.telemetry.OsdField;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RC Plus/RC Plus 2/RC Pro 遥控器 OSD 字段集构造。
 * <p>覆盖所有遥控器型号（RC Plus / RC Plus 2 / RC Pro 行业版），按型号差异化上报字段：
 * <ul>
 *   <li>三种型号共有字段：capacity_percent / latitude / longitude / height / wireless_link</li>
 *   <li>RC Plus 2 + RC Pro 独有：drc_state（DRC 链路状态）</li>
 *   <li>RC Pro 独有：country（国家码）</li>
 * </ul>
 * <p>参考文档（务必字段、类型、枚举值严格一致）：
 * <ul>
 *   <li><a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/rc-plus/properties.html">RC Plus 设备属性</a></li>
 *   <li><a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/dji-rc-plus-2/properties.html">RC Plus 2 设备属性</a></li>
 *   <li><a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/rc-pro/properties.html">RC Pro 设备属性</a></li>
 * </ul>
 * <p>字段命名风格：snake_case（与所有 Dock 版本一致，Pilot 遥控器 OSD 无 camelCase 变体）</p>
 */
@Component
public class RcPlusOsdBuilder implements RcOsdBuilder {

    @Override
    public String version() {
        return "pilot";
    }

    @Override
    public boolean supports(RcModel controllerType) {
        return controllerType != null && controllerType.isController();
    }

    @Override
    public Map<String, Object> buildRcOsd(OsdContext ctx) {
        DeviceState state = ctx.getState();
        RuntimeConfig config = ctx.getRuntimeConfig();
        RcModel controllerType = config.getControllerType();

        Map<String, Object> data = new LinkedHashMap<>();
        // 遥控器剩余电量（0-100，int）— 三种型号共有
        data.put(OsdField.CAPACITY_PERCENT.fieldName(), state.getControllerCapacity());
        // 遥控器 GPS 位置（即飞行器返航点 Home 点，由前端位置模拟面板配置）
        // RC Plus 文档 type=float，RC Plus 2/RC Pro 文档 type=double，Java double 序列化兼容两者
        data.put(OsdField.LATITUDE.fieldName(), config.getLocationLatitude());
        data.put(OsdField.LONGITUDE.fieldName(), config.getLocationLongitude());
        // 椭球高度（RC Plus 文档描述为"绝对高度"，RC Plus 2/RC Pro 描述为"椭球高度"，语义一致）
        data.put(OsdField.HEIGHT.fieldName(), config.getLocationHeight());
        // 图传链路状态（struct）— 三种型号共有，子字段集一致
        data.put(OsdField.WIRELESS_LINK.fieldName(), buildWirelessLink());

        // DRC 链路状态（enum_int: 0=未连接, 1=连接中, 2=已连接）
        // 仅 RC Plus 2 / RC Pro 有此字段（RC Plus 文档无）
        if (controllerType != RcModel.RC_PLUS) {
            data.put(OsdField.DRC_STATE.fieldName(), state.getDrcState());
        }

        // 国家码（text）— 仅 RC Pro 有此字段（RC Plus / RC Plus 2 文档无）
        if (controllerType == RcModel.RC_PRO) {
            data.put(OsdField.COUNTRY.fieldName(), "CN");
        }
        return data;
    }

    /**
     * 构造图传链路状态（wireless_link struct）。
     * <p>三种遥控器型号的 wireless_link 子字段集一致，按 RC Plus 2 文档严格对齐：
     * <ul>
     *   <li>dongle_number (int): 飞行器上 Dongle 数量</li>
     *   <li>4g_link_state (enum_int: 0=断开/未连接, 1=连接): 4G 链路连接状态</li>
     *   <li>sdr_link_state (enum_int: 0=断开/未连接, 1=连接): SDR 链路连接状态</li>
     *   <li>link_workmode (enum_int: 0=SDR 模式, 1=4G 融合模式): 机场的图传链路模式</li>
     *   <li>sdr_quality (int 0-5): SDR 信号质量</li>
     *   <li>4g_quality (int 0-5): 总体 4G 信号质量</li>
     *   <li>4g_uav_quality (int 0-5): 天端 4G 信号质量</li>
     *   <li>4g_gnd_quality (int 0-5): 地端 4G 信号质量</li>
     *   <li>sdr_freq_band (float): SDR 频段</li>
     *   <li>4g_freq_band (float): 4G 频段</li>
     * </ul>
     * <p>模拟器默认图传链路正常（4G + SDR 均已连接，信号质量满格，4G 融合模式）。</p>
     */
    private Map<String, Object> buildWirelessLink() {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("dongle_number", 1);
        link.put("4g_link_state", 1);
        link.put("sdr_link_state", 1);
        link.put("link_workmode", 1);
        link.put("sdr_quality", 5);
        link.put("4g_quality", 5);
        link.put("4g_uav_quality", 5);
        link.put("4g_gnd_quality", 5);
        link.put("sdr_freq_band", 5.8);
        link.put("4g_freq_band", 5.8);
        return link;
    }
}
