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

package ltd.cdmi.hivemind.simulator.mqtt;

import ltd.cdmi.hivemind.simulator.device.DeviceType;

/**
 * DRC 协议策略接口。
 * <p>处理 Pilot 上云内部不同遥控器的负载控制指令差异：
 * <ul>
 *   <li>RC Pro 行业版：负载控制走 services 通道，方法名无 {@code drc_} 前缀（如 {@code camera_aim}）</li>
 *   <li>RC Plus 2 / RC Plus / Dock：负载控制走 {@code drc/down} 通道，方法名有 {@code drc_} 前缀（如 {@code drc_camera_aim}）</li>
 * </ul>
 * <p>消息格式差异：
 * <ul>
 *   <li>services 通道：{@code {tid, bid, method, data, timestamp}}</li>
 *   <li>DRC 通道：{@code {method, data, seq}}</li>
 * </ul>
 * <p>核实依据：DJI Cloud API Pilot 上云指令飞行文档（RC Pro 行业版 vs RC Plus 2 行业版）。
 * RC Pro 行业版搭配 Mavic 3 行业系列，仅支持云端操控负载，负载控制走 services 通道；
 * RC Plus 2 行业版搭配 Matrice 4 系列，支持飞行和负载控制，负载控制走 DRC 通道。
 */
public interface DrcProtocol {

    /**
     * 将负载控制指令的基础方法名转换为实际方法名。
     * <p>RC Pro 行业版：{@code camera_aim} → {@code camera_aim}（无前缀）
     * <br>RC Plus 2 / Dock：{@code camera_aim} → {@code drc_camera_aim}（有 {@code drc_} 前缀）
     *
     * @param baseMethod 基础方法名（如 "camera_aim", "gimbal_reset"）
     * @return 实际方法名
     */
    String resolvePayloadMethod(String baseMethod);

    /**
     * 负载控制指令是否走 services 通道。
     * <p>RC Pro 行业版：{@code true}（走 services → services_reply）
     * <br>RC Plus 2 / Dock：{@code false}（走 drc/down → drc/up）
     *
     * @return true 表示走 services 通道，false 表示走 DRC 通道
     */
    boolean payloadUsesServiceChannel();

    /**
     * 根据遥控器类型创建对应的 DRC 协议策略。
     *
     * @param controllerType 遥控器类型
     * @return DRC 协议策略实例
     */
    static DrcProtocol forController(DeviceType controllerType) {
        if (controllerType == DeviceType.RC_PRO) {
            return new RcProDrcProtocol();
        }
        return new DrcChannelDrcProtocol();
    }
}
