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

import java.util.Map;

/**
 * 遥控器 OSD 字段集构造策略（Pilot to Cloud 专用）。
 * <p>与 {@link DockOsdBuilder} 平行，但面向 Pilot 模式的网关设备（遥控器，domain=2）。
 * <p>Pilot 遥控器 OSD 使用 snake_case 命名风格（与 Dock3 一致），不需要 {@link OsdStrategy} 转换。
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/rc-pro/properties.html">RC Pro 设备属性</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/rc-plus/properties.html">RC Plus 设备属性</a></p>
 */
public interface ControllerOsdBuilder {

    /**
     * 协议版本标识。
     * <p>用于 OSD payload 的 version 字段。Pilot 模式固定为 "pilot"。</p>
     */
    String version();

    /**
     * 判断此 Builder 是否支持指定的遥控器类型。
     * <p>用于 {@link DeviceSimulator} 按 controllerType 选择 Builder。</p>
     */
    default boolean supports(DeviceType controllerType) {
        return false;
    }

    /**
     * 构造遥控器 OSD 数据（不含 envelope，由 {@link DeviceSimulator} 包装）。
     *
     * @param ctx OSD 上下文，提供状态、配置
     * @return OSD data 字段内容，字段名使用 snake_case
     */
    Map<String, Object> buildControllerOsd(OsdContext ctx);
}
