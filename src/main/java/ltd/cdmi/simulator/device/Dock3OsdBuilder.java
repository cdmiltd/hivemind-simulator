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
 * Dock3 机场 OSD 字段集构造器。
 * <p>Dock3 特有字段：home_position_is_valid/heading/electric_supply_voltage。
 * 三版共有字段（putter_state/air_conditioner/supplement_light_state/silent_mode 等）由 {@link AbstractDockOsdBuilder} 提供。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3-properties.html">Dock3 properties</a></p>
 */
@Component
public class Dock3OsdBuilder extends AbstractDockOsdBuilder {

    @Override
    public String version() {
        return "dock3";
    }

    @Override
    public boolean supports(DeviceType dockType) {
        return dockType == DeviceType.DOCK3;
    }

    @Override
    protected void appendDockSpecific(OsdContext ctx, Map<String, Object> data) {
        OsdStrategy s = ctx.getStrategy();
        // Dock2/Dock3 共有字段（Dock1 properties 无此字段）
        data.put(s.convertKey("home_position_is_valid"), 1); // Home 点有效
        data.put(s.convertKey("heading"), 0.0);              // 机场朝向角
    }
}
