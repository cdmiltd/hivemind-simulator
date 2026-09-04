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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MQTT host 协议前缀剥离测试，对应 TDD-SPEC TC-MQTT-010。
 * <p>host 残留 {@code tcp://} 前缀会导致 broker URI 拼出 {@code tcp://tcp://...}，
 * 连接失败误报「无法链接到第三方平台」（P-2）。</p>
 */
class MqttClientManagerSchemeTest {

    @Test
    @DisplayName("剥离 tcp:// 前缀")
    void stripsTcpScheme() {
        assertEquals("localhost", MqttClientManager.stripScheme("tcp://localhost"));
        assertEquals("192.168.1.100", MqttClientManager.stripScheme("tcp://192.168.1.100"));
    }

    @Test
    @DisplayName("剥离 ssl:// 与 mqtt:// 前缀")
    void stripsOtherSchemes() {
        assertEquals("mqtt.example.com", MqttClientManager.stripScheme("ssl://mqtt.example.com"));
        assertEquals("1.2.3.4", MqttClientManager.stripScheme("mqtt://1.2.3.4"));
    }

    @Test
    @DisplayName("无前缀 host 原样返回（含首尾空白剥离）")
    void keepsBareHost() {
        assertEquals("localhost", MqttClientManager.stripScheme("localhost"));
        assertEquals("emqx", MqttClientManager.stripScheme(" emqx "));
        assertEquals("host.docker.internal", MqttClientManager.stripScheme("host.docker.internal"));
    }

    @Test
    @DisplayName("带前缀且含首尾空白时先 trim 再剥离")
    void trimsBeforeStrip() {
        assertEquals("localhost", MqttClientManager.stripScheme("  tcp://localhost  "));
    }

    @Test
    @DisplayName("容器内 localhost/127.0.0.1 自动映射 host.docker.internal（TC-MQTT-011）")
    void mapsLoopbackInContainer() {
        assertEquals("host.docker.internal", MqttClientManager.normalizeHost("localhost", true));
        assertEquals("host.docker.internal", MqttClientManager.normalizeHost("127.0.0.1", true));
        assertEquals("host.docker.internal", MqttClientManager.normalizeHost("tcp://localhost", true));
    }

    @Test
    @DisplayName("非容器环境 localhost 不映射，行为不变")
    void keepsLoopbackOutsideContainer() {
        assertEquals("localhost", MqttClientManager.normalizeHost("localhost", false));
        assertEquals("127.0.0.1", MqttClientManager.normalizeHost("127.0.0.1", false));
    }

    @Test
    @DisplayName("远程地址（第三方平台 MQTT）任何环境都不改写，直连")
    void keepsRemoteHost() {
        assertEquals("mqtt.example.com", MqttClientManager.normalizeHost("mqtt.example.com", true));
        assertEquals("192.168.1.100", MqttClientManager.normalizeHost("tcp://192.168.1.100", true));
        assertEquals("emqx", MqttClientManager.normalizeHost("emqx", true));
    }
}
