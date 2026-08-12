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
 * Dock3 机场 OSD 字段集构造器。
 * <p>Dock3 特有字段：self_converge_coordinate（自收敛坐标）。
 * Dock2/Dock3 共有字段：home_position_is_valid/heading。
 * 三版共有字段由 {@link AbstractDockOsdBuilder} 提供。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/properties.html">Dock3 properties</a></p>
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
    protected void appendDockSpecific(OsdContext ctx,
                                        Map<String, Object> powerAndBattery,
                                        Map<String, Object> taskAndLink,
                                        Map<String, Object> positionAndEnv) {
        OsdStrategy s = ctx.getStrategy();
        // Dock2/Dock3 共有字段（Dock1 properties 无此字段）
        positionAndEnv.put(s.convertKey("home_position_is_valid"), 1); // Home 点有效
        positionAndEnv.put(s.convertKey("heading"), 0.0);              // 机场朝向角
        // Dock3 特有字段（Dock1/Dock2 properties 无此字段）
        positionAndEnv.put(s.convertKey("self_converge_coordinate"), buildSelfConvergeCoordinate(ctx));
    }

    /**
     * 构造自收敛坐标（Dock3 特有，pushMode=0, r）。
     * <p>使用机场配置的经纬度和椭球高。</p>
     */
    private Map<String, Object> buildSelfConvergeCoordinate(OsdContext ctx) {
        OsdStrategy s = ctx.getStrategy();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(s.convertKey("latitude"), ctx.getProps().location().latitude());
        m.put(s.convertKey("longitude"), ctx.getProps().location().longitude());
        m.put(s.convertKey("height"), ctx.getProps().location().height());
        return m;
    }
}
