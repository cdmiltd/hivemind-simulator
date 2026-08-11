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

package ltd.cdmi.simulator.device;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Dock1 机场 OSD 字段集构造器。
 * <p>Dock1 特有字段：electric_supply_voltage（Dock2 无此字段，Dock3 有此字段）。
 * 三版共有字段（putter_state/air_conditioner/supplement_light_state/silent_mode 等）由 {@link AbstractDockOsdBuilder} 提供。</p>
 * <p>Dock1 的 sub_device 使用 product_type 字段名（Dock2/Dock3 使用 device_model_key）。</p>
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
        return "product_type"; // Dock1 使用 product_type，Dock2/Dock3 使用 device_model_key
    }

    @Override
    protected void appendDockSpecific(OsdContext ctx, Map<String, Object> data) {
        OsdStrategy s = ctx.getStrategy();
        DeviceState state = ctx.getState();
        // Dock1 特有字段（Dock2/Dock3 properties 列表均无此二字段，仅 Dock1 有）
        data.put(s.convertKey("putter_state"), state.isPutterExpanded() ? 1 : 0);
        data.put(s.convertKey("electric_supply_voltage"), state.getElectricSupplyVoltage());
    }
}
