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

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dock1 机场 OSD 字段集构造器。
 * <p>Dock1 特有字段：drone_authority_info（飞行器控制权状态，control_source + locked 在 OSD，payloads 在 state）。
 * electric_supply_voltage 是 OSD 封面字段（三版共有，由 {@link AbstractDockOsdBuilder} 提供）。</p>
 * <p>sub_device 字段名三版统一使用 device_model_key（Dock1 属性列表标注 product_type，但 OSD 示例用 device_model_key，待真机验证）。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html">Dock1 properties</a></p>
 */
@Component
public class Dock1OsdBuilder extends AbstractDockOsdBuilder {

    @Override
    public String version() {
        return "dock1"; // Dock1/Dock2 共用 camelCase 策略
    }

    @Override
    public boolean supports(DeviceType dockType) {
        return dockType == DeviceType.DOCK1;
    }

    @Override
    protected String subDeviceModelKeyField() {
        return "device_model_key"; // 三版统一使用 device_model_key（Dock1 属性列表标注 product_type，但 OSD 示例用 device_model_key，待真机验证）
    }

    @Override
    protected void appendDockSpecific(OsdContext ctx,
                                        Map<String, Object> powerAndBattery,
                                        Map<String, Object> taskAndLink,
                                        Map<String, Object> positionAndEnv) {
        OsdStrategy s = ctx.getStrategy();
        // Dock1 特有字段：drone_authority_info（飞行器控制权状态，pushMode=0 子字段在 OSD）
        positionAndEnv.put(s.convertKey("drone_authority_info"), buildDroneAuthorityInfo(s));
    }

    /**
     * 构造 drone_authority_info（飞行器控制权状态）。
     * <p>OSD 上报 control_source + locked（pushMode=0）；payloads（pushMode=1）由 state topic 上报。</p>
     */
    private Map<String, Object> buildDroneAuthorityInfo(OsdStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("control_source"), "A");  // 飞行控制权（A控）
        m.put(s.convertKey("locked"), false);          // 飞行控制权未锁定
        return m;
    }
}
