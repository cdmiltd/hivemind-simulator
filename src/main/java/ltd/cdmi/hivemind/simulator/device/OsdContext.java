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

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;

/**
 * OSD 构造上下文，封装 Builder 所需的依赖。
 * <p>每次 OSD 上报时由 {@link DeviceSimulator} 创建，传递给 {@link DockOsdBuilder} / {@link DroneOsdBuilder}。
 * <p>Builder 通过此对象访问设备状态、配置、命名策略，避免直接注入 Spring Bean，符合 facade 模式。</p>
 */
public class OsdContext {

    private final DeviceState state;
    private final SimulatorProperties props;
    private final RuntimeConfig runtimeConfig;
    private final OsdStrategy strategy;

    public OsdContext(DeviceState state, SimulatorProperties props, RuntimeConfig runtimeConfig, OsdStrategy strategy) {
        this.state = state;
        this.props = props;
        this.runtimeConfig = runtimeConfig;
        this.strategy = strategy;
    }

    public DeviceState getState() { return state; }
    public SimulatorProperties getProps() { return props; }
    public RuntimeConfig getRuntimeConfig() { return runtimeConfig; }
    public OsdStrategy getStrategy() { return strategy; }

    /** 当前机场类型 */
    public DeviceType getDockType() { return runtimeConfig.getDockType(); }

    /** 当前飞行器类型 */
    public DeviceType getDroneType() { return runtimeConfig.getDroneType(); }

    /** 是否为红外机型（sub_type=1，如 M4TD/M30T/M3TD） */
    public boolean isThermal() {
        DeviceType drone = getDroneType();
        return drone != null && drone.isAircraft() && drone.getSubType() == 1;
    }
}
