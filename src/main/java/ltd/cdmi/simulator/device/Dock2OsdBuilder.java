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
 * Dock2 机场 OSD 字段集构造器。
 * <p>Dock2 特有字段：home_position_is_valid/heading（Dock1 无此字段，Dock3 也有此字段）。
 * 三版共有字段（putter_state/air_conditioner/supplement_light_state/silent_mode 等）由 {@link AbstractDockOsdBuilder} 提供。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html">Dock2 properties</a></p>
 */
@Component
public class Dock2OsdBuilder extends AbstractDockOsdBuilder {

    @Override
    public String version() {
        return "dock1"; // Dock1/Dock2 共用 camelCase 策略
    }

    @Override
    public boolean supports(DeviceType dockType) {
        return dockType == DeviceType.DOCK2;
    }

    @Override
    protected void appendDockSpecific(OsdContext ctx, Map<String, Object> data) {
        OsdStrategy s = ctx.getStrategy();
        // Dock2 特有字段（Dock1 无此字段）
        data.put(s.convertKey("home_position_is_valid"), 1); // Home 点有效
        data.put(s.convertKey("heading"), 0.0);              // 机场朝向角
    }
}
