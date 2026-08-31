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

package ltd.cdmi.hivemind.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQTT 公共配置（模拟器与监控器共享）。
 * <p>连接参数（host/port/username/password）为公共默认值：
 * <ul>
 *   <li>模拟器：直接使用本配置</li>
 *   <li>监控器：作为默认值，前端可覆盖</li>
 * </ul>
 * client-id-prefix 按角色区分，避免模拟器与监控器 clientId 冲突。</p>
 */
@ConfigurationProperties(prefix = "mqtt")
public record MqttProperties(
        String host,
        int port,
        String username,
        String password,
        /** 模拟器 MQTT 客户端 ID 前缀 */
        String simulatorClientIdPrefix,
        /** 监控器 MQTT 客户端 ID 前缀 */
        String monitorClientIdPrefix
) {}
