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

package ltd.cdmi.hivemind.simulator.ws;

import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MOP WebSocket 客户端测试。
 * <p>覆盖 MopClient 的连接管理行为：未配置 host 时跳过连接、连接状态查询、
 * 未连接时发送消息返回失败、断开连接的安全性。host/token 的 getter/setter 正确性
 * 见 {@link ltd.cdmi.hivemind.simulator.config.PilotConfigTest}。
 */
class MopClientTest {

    /** 构造一个 mop 配置为空的 RuntimeConfig（host/token 均为空） */
    private RuntimeConfig newRuntimeConfigWithEmptyMop() {
        return new RuntimeConfig(testMqttProps(), testProps(), new LiveConfigStore());
    }

    private MqttProperties testMqttProps() {
        return new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-");
    }

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null
        );
    }

    // ==================== 连接状态 ====================

    @Test
    void isConnectedReturnsFalseInitially() {
        MopClient mopClient = new MopClient(newRuntimeConfigWithEmptyMop());
        assertFalse(mopClient.isConnected(), "新建 MopClient 连接状态应为 false");
    }

    // ==================== connect：未配置 host 时跳过连接 ====================

    @Test
    void connectSkipsWhenHostNotConfigured() {
        RuntimeConfig rc = newRuntimeConfigWithEmptyMop();
        rc.setMopHost("");   // 显式置空
        MopClient mopClient = new MopClient(rc);
        mopClient.connect();
        // host 为空，connect() 跳过连接，状态保持 false
        assertFalse(mopClient.isConnected(), "host 未配置时不应建立连接");
    }

    @Test
    void connectSkipsWhenHostBlank() {
        RuntimeConfig rc = newRuntimeConfigWithEmptyMop();
        rc.setMopHost("   ");   // 纯空白
        MopClient mopClient = new MopClient(rc);
        mopClient.connect();
        assertFalse(mopClient.isConnected(), "host 为纯空白时不应建立连接");
    }

    // ==================== sendMessage：未连接时返回失败 ====================

    @Test
    void sendMessageReturnsFalseWhenNotConnected() {
        MopClient mopClient = new MopClient(newRuntimeConfigWithEmptyMop());
        boolean result = mopClient.sendMessage("hello mop");
        assertFalse(result, "未连接时 sendMessage 应返回 false");
    }

    // ==================== disconnect：安全性 ====================

    @Test
    void disconnectIsSafeWhenNeverConnected() {
        MopClient mopClient = new MopClient(newRuntimeConfigWithEmptyMop());
        // 从未连接时调用 disconnect 不应抛异常
        assertDoesNotThrow(mopClient::disconnect);
        assertFalse(mopClient.isConnected(), "disconnect 后连接状态应为 false");
    }
}
