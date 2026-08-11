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
 * 机场 OSD 字段集构造策略，按 Dock 版本划分字段集。
 * <p>与 {@link OsdStrategy}（字段命名风格）正交：OsdStrategy 管字段"怎么命名"，
 * DockOsdBuilder 管字段"有哪些"。OsdStrategy 通过 {@link OsdContext} 注入复用。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html">Dock1 properties</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html">Dock2 properties</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3-properties.html">Dock3 properties</a></p>
 */
public interface DockOsdBuilder {

    /**
     * 协议版本标识，与 {@link OsdStrategy#version()} 对齐。
     * <p>用于 OSD payload 的 version 字段。Dock1/Dock2 共用 "dock1"（camelCase 策略），Dock3 用 "dock3"。</p>
     */
    String version();

    /**
     * 判断此 Builder 是否支持指定的机场类型。
     * <p>用于 {@link DeviceSimulator} 按 dockType 选择 Builder，与 {@link #version()} 解耦。</p>
     */
    default boolean supports(DeviceType dockType) {
        return false;
    }

    /**
     * 构造机场 OSD 数据（不含 envelope，由 {@link DeviceSimulator} 包装）。
     *
     * @param ctx OSD 上下文，提供状态、配置、命名策略
     * @return OSD data 字段内容，字段名经 {@link OsdStrategy#convertKey(String)} 转换
     */
    Map<String, Object> buildDockOsd(OsdContext ctx);
}
