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

/**
 * DRC 通道的 DRC 协议策略。
 * <p>负载控制指令走 {@code drc/down} 通道，方法名有 {@code drc_} 前缀。
 * <p>适用于 RC Plus 2 行业版、RC Plus 和 Dock 上云场景。
 * <p>核实依据：DJI Cloud API Pilot 上云 - DJI RC Plus 2 行业版远程控制文档 + Dock3 远程控制文档。
 * RC Plus 2 行业版搭配 Matrice 4 系列，支持飞行和负载控制，负载控制走 DRC 通道。
 * <p>消息格式：{@code {method, data, seq}}（DRC 通道格式）。
 */
public class DrcChannelDrcProtocol implements DrcProtocol {

    @Override
    public String resolvePayloadMethod(String baseMethod) {
        return "drc_" + baseMethod;
    }

    @Override
    public boolean payloadUsesServiceChannel() {
        return false;
    }
}
