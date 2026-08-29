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

package ltd.cdmi.hivemind.simulator.device.osd;

import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.hivemind.simulator.device.DefaultCameraResolver;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;

/**
 * OSD 构造上下文，封装 Builder 所需的依赖。
 * <p>每次 OSD 上报时由 {@link DeviceSimulator} 创建，传递给 {@link DockOsdBuilder} / {@link DroneOsdBuilder}。
 * <p>Builder 通过此对象访问设备状态、配置，避免直接注入 Spring Bean，符合 facade 模式。</p>
 */
public class OsdContext {

    private final DeviceState state;
    private final SimulatorProperties props;
    private final RuntimeConfig runtimeConfig;

    public OsdContext(DeviceState state, SimulatorProperties props, RuntimeConfig runtimeConfig) {
        this.state = state;
        this.props = props;
        this.runtimeConfig = runtimeConfig;
    }

    public DeviceState getState() { return state; }
    public SimulatorProperties getProps() { return props; }
    public RuntimeConfig getRuntimeConfig() { return runtimeConfig; }

    /** 当前飞行器类型 */
    public DroneModel getDroneType() { return runtimeConfig.getDroneType(); }

    /**
     * 是否为红外（热成像）场景。
     * <p>判断逻辑：
     * <ol>
     *   <li>飞行器 sub_type=1（内置热成像机型，如 M4TD/M30T/M3TD/Mavic 3T/M4T）→ true</li>
     *   <li>用户选择的相机负载为热成像型号（H20T/H20N/H30T 等，M350/M300 可挂载）→ true</li>
     * </ol>
     * <p>覆盖场景：M350 RTK / M300 RTK 搭载 H20T 时，sub_type=0 但相机为热成像，应返回 true。
     */
    public boolean isThermal() {
        DroneModel drone = getDroneType();
        if (drone != null && drone.isAircraft() && drone.subType() == 1) {
            return true;
        }
        PayloadType payload = getSelectedPayload();
        return payload != null && payload.isThermal();
    }

    /**
     * 当前生效的负载：优先返回用户选择的负载（M350/M300 可挂载通用云台），
     * 未选择时回退到飞行器默认主相机。
     */
    public PayloadType getSelectedPayload() {
        PayloadType selected = runtimeConfig.getSelectedPayload();
        return selected != null ? selected : DefaultCameraResolver.defaultCameraFor(getDroneType());
    }
}
