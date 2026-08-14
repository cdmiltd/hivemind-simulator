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

import java.util.Map;

/**
 * 飞行器 state 字段集构造策略，按机型家族划分 pushMode=1 字段集。
 * <p>与 {@link DroneOsdBuilder}（OSD 字段集）正交：
 * DroneOsdBuilder 管 pushMode=0 字段（OSD topic），DroneStateBuilder 管 pushMode=1 字段（state topic）。</p>
 * <p>Pilot 模式下，{@link PilotOnlineService#publishDroneState()} 遍历所有 DroneStateBuilder，
 * 选择第一个 supports 返回 true 的进行构造。</p>
 * <p>不同机型的 state 字段集可能不同（如 Mavic 3 的 firmware_version 是 pushMode=1，
 * 而 Matrice 4 系列的 firmware_version 是 pushMode=0），因此按机型区分。</p>
 */
public interface DroneStateBuilder {

    /**
     * 机型家族标识，如 "mavic3"、"m4e-m4t"。
     * <p>用于日志和调试，不参与 Builder 选择（选择基于 {@link #supports(DeviceType)}）。</p>
     */
    String aircraftFamily();

    /**
     * 判断此 Builder 是否支持指定的飞行器类型。
     *
     * @param droneType 飞行器类型
     * @return true 表示此 Builder 可构造该机型的 state
     */
    boolean supports(DeviceType droneType);

    /**
     * 构造飞行器 state 数据（pushMode=1 字段，不含 envelope）。
     *
     * @param runtimeConfig 运行时配置，提供 Home 点经纬度等
     * @return state data 字段内容
     */
    Map<String, Object> buildDroneState(RuntimeConfig runtimeConfig);
}
