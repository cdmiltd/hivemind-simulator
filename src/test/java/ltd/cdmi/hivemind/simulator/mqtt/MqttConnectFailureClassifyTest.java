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

import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MQTT 初始连接失败分类测试，对应 TDD-SPEC TC-MQTT-012。
 * <p>实证（Docker Desktop + 同机平台部署）：容器经宿主机 IP 回环访问端口映射到另一容器的
 * MQTT broker，TCP 握手成功但 CONNECT 转发被断开，Paho 报 32109（Connection lost），
 * broker 侧无连接记录——属网络路径问题而非凭证错误（正确凭证同样失败）。
 * 认证拒绝时 broker 会正常回 CONNACK（Paho 报 32104/32105），不表现为 32109。</p>
 */
class MqttConnectFailureClassifyTest {

    @Test
    @DisplayName("CONNACK 4/5（32104/32105）归类为凭证错误")
    void authErrorCodes() {
        assertEquals(DiagnosticCode.PLATFORM_AUTH_FAILED,
                MqttClientManager.classifyConnectFailure(MqttException.REASON_CODE_FAILED_AUTHENTICATION));
        assertEquals(DiagnosticCode.PLATFORM_AUTH_FAILED,
                MqttClientManager.classifyConnectFailure(MqttException.REASON_CODE_NOT_AUTHORIZED));
    }

    @Test
    @DisplayName("32109 Connection lost（发夹回环网络路径断开）归类为网络问题而非凭证错误")
    void connectionLostIsNetworkIssue() {
        assertEquals(DiagnosticCode.PLATFORM_HOST_UNREACHABLE,
                MqttClientManager.classifyConnectFailure(MqttException.REASON_CODE_CONNECTION_LOST));
    }

    @Test
    @DisplayName("32103 等连接不上场景仍归类为地址不可达")
    void unreachableStaysHostError() {
        assertEquals(DiagnosticCode.PLATFORM_HOST_UNREACHABLE,
                MqttClientManager.classifyConnectFailure(MqttException.REASON_CODE_SERVER_CONNECT_ERROR));
    }
}
