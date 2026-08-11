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
 * 飞行器 OSD 字段集构造策略，按机型家族划分字段集。
 * <p>与 {@link DockOsdBuilder}（机场字段集）和 {@link OsdStrategy}（字段命名）正交。
 * 三者通过 {@link OsdContext} 协作：OsdStrategy 管命名，DockOsdBuilder 管机场字段，DroneOsdBuilder 管飞行器字段。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m4d-properties.html">M4D properties</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html">M30 properties</a></p>
 */
public interface DroneOsdBuilder {

    /**
     * 机型家族标识，如 "m4d"、"m30"、"m3d"。
     * <p>用于日志和调试，不参与 Builder 选择（选择基于 {@link #supports(DeviceType)}）。</p>
     */
    String aircraftFamily();

    /**
     * 判断此 Builder 是否支持指定的飞行器类型。
     * <p>{@link DeviceSimulator} 遍历所有 DroneOsdBuilder，选择第一个 supports 返回 true 的。</p>
     *
     * @param droneType 飞行器类型
     * @return true 表示此 Builder 可构造该机型的 OSD
     */
    boolean supports(DeviceType droneType);

    /**
     * 构造飞行器 OSD 数据（不含 envelope，由 {@link DeviceSimulator} 包装）。
     *
     * @param ctx OSD 上下文，提供状态、配置、命名策略
     * @return OSD data 字段内容，字段名经 {@link OsdStrategy#convertKey(String)} 转换
     */
    Map<String, Object> buildDroneOsd(OsdContext ctx);
}
