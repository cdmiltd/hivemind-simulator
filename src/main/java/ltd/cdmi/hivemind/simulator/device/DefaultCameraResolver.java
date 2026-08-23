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

import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;

/**
 * 机型到默认相机的配置决策。
 *
 * <p>属于模拟器的应用层配置逻辑——DJI 产品支持文档只列出"某机型可搭载哪些相机"，
 * 但"默认配什么"是模拟器的决策。例如 M350 RTK 可搭载 H20/H20T/H20N/H30/H30T，
 * 模拟器默认选择 H20（最基础型号）。
 *
 * <p>决策规则：
 * <ul>
 *   <li>飞行器主相机（如 M30→M30_CAMERA）：通过 {@link PayloadType#compatibleAircraft()} 匹配</li>
 *   <li>通用云台负载机型（M400/M350/M300）：指定默认型号（M400→H30，M350/M300→H20）</li>
 *   <li>无对应相机的机型：返回 null</li>
 * </ul>
 *
 * @see PayloadType
 */
public final class DefaultCameraResolver {

    private DefaultCameraResolver() {}

    /**
     * 获取飞行器默认主相机。
     *
     * @param aircraft 飞行器型号
     * @return 默认相机负载类型，无对应相机时返回 null
     */
    public static PayloadType defaultCameraFor(DroneModel aircraft) {
        if (aircraft == null || !aircraft.isAircraft()) return null;
        // M400 搭载 H30/H30T 通用云台负载（DJI 产品支持文档），默认 H30（82-0-0，非热成像）
        if (aircraft == DroneModel.M400) return PayloadType.H30;
        // M350 RTK / M300 RTK 搭载 H20/H20T/H20N/H30/H30T 通用云台负载（DJI 产品支持文档），默认 H20（42-0-0，最基础型号）
        if (aircraft == DroneModel.M350_RTK || aircraft == DroneModel.M300_RTK) return PayloadType.H20;
        for (PayloadType p : PayloadType.values()) {
            if (p.compatibleAircraft() == aircraft) {
                return p;
            }
        }
        return null;
    }
}
