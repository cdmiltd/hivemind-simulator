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

/**
 * OSD 序列化策略，按 Dock 版本切换字段命名风格。
 * <p>Dock3 用 snake_case，Dock1/Dock2 用 camelCase。</p>
 */
public interface OsdStrategy {
    /** 将标准 snake_case 字段名转换为该 Dock 版本要求的格式 */
    String convertKey(String snakeCaseKey);

    /** OSD envelope 的 version 字段值 */
    String version();
}
