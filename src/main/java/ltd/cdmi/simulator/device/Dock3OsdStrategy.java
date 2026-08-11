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

/**
 * Dock3 OSD 策略：字段名使用 snake_case（原样返回）。
 * <p>对应 DJI 大疆机场3（Dock3）的 OSD 协议格式。</p>
 */
@Component
public class Dock3OsdStrategy implements OsdStrategy {

    @Override
    public String convertKey(String snakeCaseKey) {
        return snakeCaseKey;
    }

    @Override
    public String version() {
        return "dock3";
    }
}
