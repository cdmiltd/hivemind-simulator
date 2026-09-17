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

/**
 * 设备接入模式枚举。
 * <p>Dock to Cloud：机场作为网关设备（domain=3），通过 MQTT 直连 + 设备绑定流程接入
 * <p>Pilot to Cloud：遥控器作为网关设备（domain=2），通过 JSBridge + MQTT 接入（模拟器跳过 JSBridge）
 * <p>模拟器单实例运行，通过此枚举切换模式，不支持同时运行两种模式。
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/overview/product-support.html">DJI 产品支持</a>
 */
public enum DeviceMode {
    /** 机场上云模式（Dock to Cloud）：网关为机场，支持完整注册流程、航线任务、HMS、远程调试 */
    DOCK,
    /** 飞行器上云模式（Pilot to Cloud）：网关为遥控器，跳过注册流程，航线/媒体走 HTTPS，DRC 走 MQTT */
    PILOT
}
