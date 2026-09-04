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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监控器 MQTT 连接状态唯一真相源测试（TDD-SPEC TC-MQTT-015）。
 * <p>验证 {@code isConnected()} 完全委托 Paho 实际状态（无冗余自定义标志）：
 * 断线（connectionLost）与自动重连（connectComplete）后状态自动跟随 Paho，
 * 不存在双状态源不同步（曾导致"显示未连接但消息正常接收"）。</p>
 * <p>通过反射注入 mock {@link MqttClient}，隔离真实 broker 依赖。</p>
 */
class MonitorMqttClientConnectionStateTest {

    private MonitorMqttClient newClient() {
        return new MonitorMqttClient(new ObjectMapper(), null, 100);
    }

    /** 反射注入 mock Paho client */
    private void injectClient(MonitorMqttClient monitorClient, MqttClient mockPaho) throws Exception {
        Field f = MonitorMqttClient.class.getDeclaredField("client");
        f.setAccessible(true);
        f.set(monitorClient, mockPaho);
    }

    @DisplayName("TC-MQTT-015：isConnected 完全委托 Paho 状态——Paho 连接则为 true")
    @Test
    void isConnected_delegatesToPahoConnected() throws Exception {
        MonitorMqttClient monitorClient = newClient();
        MqttClient paho = Mockito.mock(MqttClient.class);
        Mockito.when(paho.isConnected()).thenReturn(true);
        injectClient(monitorClient, paho);

        assertTrue(monitorClient.isConnected(), "Paho isConnected=true 时监控器应报告已连接");
    }

    @DisplayName("TC-MQTT-015：断线场景——connectionLost 后状态跟随 Paho（false），不依赖自定义标志")
    @Test
    void connectionLost_stateFollowsPaho() throws Exception {
        MonitorMqttClient monitorClient = newClient();
        MqttClient paho = Mockito.mock(MqttClient.class);
        Mockito.when(paho.isConnected()).thenReturn(true);
        injectClient(monitorClient, paho);
        assertTrue(monitorClient.isConnected(), "连接中");

        // 网络闪断：Paho 置 isConnected=false，触发 connectionLost 回调
        Mockito.when(paho.isConnected()).thenReturn(false);
        monitorClient.connectionLost(new RuntimeException("网络闪断"));

        assertFalse(monitorClient.isConnected(), "断线后 isConnected 应跟随 Paho=false");
    }

    @DisplayName("TC-MQTT-015：自动重连场景——connectComplete 后状态跟随 Paho 恢复 true（原 Bug：标志未恢复）")
    @Test
    void autoReconnect_stateFollowsPahoRecovered() throws Exception {
        MonitorMqttClient monitorClient = newClient();
        MqttClient paho = Mockito.mock(MqttClient.class);
        Mockito.when(paho.isConnected()).thenReturn(true);
        injectClient(monitorClient, paho);

        // 断线
        Mockito.when(paho.isConnected()).thenReturn(false);
        monitorClient.connectionLost(new RuntimeException("网络闪断"));
        assertFalse(monitorClient.isConnected(), "断线中");

        // Paho 自动重连成功：isConnected 恢复 true，connectComplete 重新订阅（不修改状态标志）
        Mockito.when(paho.isConnected()).thenReturn(true);
        monitorClient.connectComplete(true, "tcp://broker:1883");

        assertTrue(monitorClient.isConnected(),
                "自动重连成功后 isConnected 应恢复 true（原 Bug：connected 标志在 connectComplete 中未恢复）");
        Mockito.verify(paho, Mockito.atLeastOnce()).subscribe(Mockito.anyString(), Mockito.anyInt());
    }

    @DisplayName("TC-MQTT-015：disconnect 后 client=null，isConnected=false")
    @Test
    void disconnect_clearsClient() throws Exception {
        MonitorMqttClient monitorClient = newClient();
        MqttClient paho = Mockito.mock(MqttClient.class);
        Mockito.when(paho.isConnected()).thenReturn(true);
        injectClient(monitorClient, paho);

        monitorClient.disconnect();

        assertFalse(monitorClient.isConnected(), "disconnect 后应报告未连接");
        Mockito.verify(paho).disconnect();
        Mockito.verify(paho).close();
    }

    @DisplayName("TC-MQTT-015：未初始化（client=null）时 isConnected=false")
    @Test
    void neverConnected_reportsFalse() {
        MonitorMqttClient monitorClient = newClient();
        assertFalse(monitorClient.isConnected(), "未连接过应报告未连接");
    }
}
