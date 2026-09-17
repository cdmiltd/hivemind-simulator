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
 * RC Pro 行业版的 DRC 协议策略。
 * <p>负载控制指令走 services 通道，方法名无 {@code drc_} 前缀。
 * <p>核实依据：DJI Cloud API Pilot 上云 - DJI RC Pro 行业版指令飞行文档。
 * RC Pro 行业版搭配 Mavic 3 行业系列，仅支持云端操控负载，负载控制走 services 通道。
 * <p>消息格式：{@code {tid, bid, method, data, timestamp}}（services 通道标准格式）。
 */
public class RcProDrcProtocol implements DrcProtocol {

    @Override
    public String resolvePayloadMethod(String baseMethod) {
        return baseMethod;
    }

    @Override
    public boolean payloadUsesServiceChannel() {
        return true;
    }
}
