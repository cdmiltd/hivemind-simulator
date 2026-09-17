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

import ltd.cdmi.dji.cloudapi.sdk.command.service.drc.DrcMqttBroker;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DrcConnectionManager 单元测试：DRC 专用 MQTT 连接生命周期（TC-DRC-067~074）。
 * <p>覆盖：建连并订阅 drc/down（067）、重复进入断旧连（068）、退出断开（069）、
 * expire_time 凭证语义（070，DJI 文档：单位秒、到期不断连）、drc/up 发布路由（071）、
 * 建连失败容错（072）、专用连接消息处理与流量日志（073 关联）、关机联动幂等（074）。</p>
 */
class DrcConnectionManagerTest {

    private static final String DRC_CLIENT_ID = "drc-dock-sn-test";
    private static final String DRC_UP_TOPIC = "thing/product/dock-sn-test/drc/up";

    private MqttClientManager mqtt;
    private DiagnosticLogRecorder diagnosticRecorder;
    private RuntimeConfig runtimeConfig;
    private DockTopicSchema schema;
    private TestableManager manager;

    /** 可注入 mock IMqttClient 的测试子类（记录建连 URI/凭证与回调，可模拟建连失败） */
    static class TestableManager extends DrcConnectionManager {
        final List<IMqttClient> createdClients = new ArrayList<>();
        final List<String> brokerUris = new ArrayList<>();
        final List<String> clientIds = new ArrayList<>();
        volatile MqttCallback lastCallback;
        boolean failConnect;

        TestableManager(MqttClientManager mqtt, RuntimeConfig rc, DockTopicSchema schema,
                        DiagnosticLogRecorder recorder) {
            super(mqtt, rc, schema, recorder);
        }

        @Override
        protected IMqttClient createClient(String brokerUri, String clientId) throws MqttException {
            brokerUris.add(brokerUri);
            clientIds.add(clientId);
            if (failConnect) {
                throw new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION);
            }
            IMqttClient client = Mockito.mock(IMqttClient.class);
            doAnswer(inv -> {
                lastCallback = inv.getArgument(0);
                return null;
            }).when(client).setCallback(any(MqttCallback.class));
            createdClients.add(client);
            return client;
        }
    }

    @BeforeEach
    void setUp() {
        mqtt = Mockito.mock(MqttClientManager.class);
        diagnosticRecorder = Mockito.mock(DiagnosticLogRecorder.class);
        runtimeConfig = new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
        schema = new DockTopicSchema();
        manager = new TestableManager(mqtt, runtimeConfig, schema, diagnosticRecorder);
    }

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null, null);
    }

    private DrcMqttBroker broker(Long expireTime) {
        // SDK 放松校验后：username/password/enableTls/expireTime 均可空；此处带全量字段验证正常路径
        return new DrcMqttBroker("emqx:1883", DRC_CLIENT_ID, "drc-user", "drc-pass", false, expireTime);
    }

    private String expectedDrcDownTopic() {
        return schema.topic(schema.drcDown(), runtimeConfig.getGatewaySn());
    }

    // ==================== TC-DRC-067：建连并订阅 drc/down ====================

    @Test
    @DisplayName("TC-DRC-067a: enter(null) 跳过建连，DRC 消息走主连接（诊断日志留痕）")
    void enterWithoutBrokerSkipsConnection() {
        manager.enter(null);

        assertFalse(manager.isActive(), "未携带 mqtt_broker 不应激活专用连接路由");
        assertTrue(manager.createdClients.isEmpty(), "不应创建任何专用连接");
        verify(diagnosticRecorder).record(eq(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE),
                eq("drc_mode_enter"), anyString());
    }

    @Test
    @DisplayName("TC-DRC-067b: enter 异步建连，用平台凭证连接并订阅 drc/down（QoS 1），username/password 传递")
    void enterConnectsAndSubscribesDrcDown() throws Exception {
        manager.enter(broker(4700000000L));

        // 异步建连：轮询等待专用连接创建（上限 2 秒）
        long deadline = System.currentTimeMillis() + 2000;
        while (manager.createdClients.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        IMqttClient client = manager.createdClients.get(0);
        verify(client, timeout(2000)).connect(any(org.eclipse.paho.client.mqttv3.MqttConnectOptions.class));
        verify(client, timeout(2000)).subscribe(expectedDrcDownTopic(), 1);
        assertTrue(manager.isActive(), "建连成功后路由应激活");
        assertEquals("tcp://emqx:1883", manager.brokerUris.get(0), "无 TLS 拼接 tcp://，scheme 剥离后拼接");
        assertEquals(DRC_CLIENT_ID, manager.clientIds.get(0), "client_id 使用平台下发值（EMQX 可见 drc-{sn}）");

        // username/password 应传递给连接选项（TC-DRC-067 补充：SDK 补齐 password 字段）
        ArgumentCaptor<org.eclipse.paho.client.mqttv3.MqttConnectOptions> optCaptor =
                ArgumentCaptor.forClass(org.eclipse.paho.client.mqttv3.MqttConnectOptions.class);
        verify(client).connect(optCaptor.capture());
        assertEquals("drc-user", optCaptor.getValue().getUserName());
        assertEquals("drc-pass", new String(optCaptor.getValue().getPassword()));
    }

    // ==================== TC-DRC-068：重复进入断旧连 ====================

    @Test
    @DisplayName("TC-DRC-068: 重复 enter 先断旧连再建新连，无僵尸连接")
    void reEnterDisconnectsOldConnection() throws Exception {
        manager.connectNow(broker(0L));
        IMqttClient first = manager.createdClients.get(0);
        assertTrue(manager.isActive());

        manager.connectNow(broker(0L));
        IMqttClient second = manager.createdClients.get(1);

        verify(first).close();
        assertTrue(manager.isActive(), "新连接就绪后路由保持激活");
        assertNotSame(first, second, "应创建新的专用连接");
    }

    // ==================== TC-DRC-069：退出断开 ====================

    @Test
    @DisplayName("TC-DRC-069: disconnect 后路由回退主连接（isActive=false）")
    void disconnectDeactivatesRouting() throws Exception {
        manager.connectNow(broker(0L));
        assertTrue(manager.isActive());

        manager.disconnect("drc_mode_exit");

        assertFalse(manager.isActive(), "断开后 drc/down 应恢复主连接处理");
        verify(manager.createdClients.get(0)).close();
    }

    // ==================== TC-DRC-070：expire_time 凭证语义（DJI 文档：到期不断连） ====================

    @Test
    @DisplayName("TC-DRC-070: expire_time 已过期不断开已建立连接（文档：过期不影响已建立连接的设备）")
    void expiredCredentialDoesNotDisconnect() throws Exception {
        long expiredSeconds = System.currentTimeMillis() / 1000 - 10;
        manager.connectNow(broker(expiredSeconds));
        Thread.sleep(100);
        assertTrue(manager.isActive(), "凭证过期不应断开已建立的连接（DJI 文档语义）");
    }

    @Test
    @DisplayName("TC-DRC-070b: expire_time 缺省（null）不断开（SDK 放松校验后容错）")
    void nullExpireTimeKeepsConnection() throws Exception {
        DrcMqttBroker noExpire = new DrcMqttBroker("emqx:1883", DRC_CLIENT_ID, "drc-user", null, false, null);
        manager.connectNow(noExpire);
        Thread.sleep(100);
        assertTrue(manager.isActive(), "expire_time=null 不触发断开");
    }

    // ==================== TC-DRC-071：drc/up 发布路由 ====================

    @Test
    @DisplayName("TC-DRC-071a: 专用连接在线时 drc/up 从专用连接发布（QoS 1）并记录流量日志")
    void publishUpRoutesToDedicatedConnection() throws Exception {
        manager.connectNow(broker(0L));
        IMqttClient client = manager.createdClients.get(0);
        when(client.isConnected()).thenReturn(true);

        manager.publishUp(DRC_UP_TOPIC, "{\"method\":\"heart_beat\"}");

        ArgumentCaptor<MqttMessage> msgCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(client).publish(eq(DRC_UP_TOPIC), msgCaptor.capture());
        assertEquals(1, msgCaptor.getValue().getQos(), "drc/up 应以 QoS 1 发布");
        assertEquals("{\"method\":\"heart_beat\"}",
                new String(msgCaptor.getValue().getPayload(), StandardCharsets.UTF_8));
        verify(mqtt).logTraffic("send", DRC_UP_TOPIC, "{\"method\":\"heart_beat\"}");
        verify(mqtt, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("TC-DRC-071b: 专用连接未激活时 drc/up 回退主连接")
    void publishUpFallsBackToMainConnection() {
        assertFalse(manager.isActive());

        manager.publishUp(DRC_UP_TOPIC, "{\"method\":\"heart_beat\"}");

        verify(mqtt).publish(DRC_UP_TOPIC, "{\"method\":\"heart_beat\"}");
        verify(mqtt, never()).logTraffic(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("TC-DRC-071c: 专用连接发布失败时回退主连接")
    void publishUpFallsBackOnPublishFailure() throws Exception {
        manager.connectNow(broker(0L));
        IMqttClient client = manager.createdClients.get(0);
        when(client.isConnected()).thenReturn(true);
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION))
                .when(client).publish(anyString(), any(MqttMessage.class));

        manager.publishUp(DRC_UP_TOPIC, "{\"method\":\"heart_beat\"}");

        verify(mqtt).publish(DRC_UP_TOPIC, "{\"method\":\"heart_beat\"}");
    }

    // ==================== TC-DRC-072：建连失败容错 ====================

    @Test
    @DisplayName("TC-DRC-072: 建连失败不抛异常，路由回退主连接并记录 M-2 诊断日志")
    void connectFailureDoesNotBlockDrcMode() {
        manager.failConnect = true;

        assertDoesNotThrow(() -> manager.connectNow(broker(0L)));
        assertFalse(manager.isActive(), "建连失败后消息回退主连接");
        verify(diagnosticRecorder).record(eq(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE),
                eq("drc_mode_enter"), anyString());
    }

    // ==================== TC-DRC-073（关联）：专用连接消息处理与流量日志 ====================

    @Test
    @DisplayName("TC-DRC-073: 专用连接收到 drc/down 后回调处理器并记录 recv 流量日志")
    void dedicatedConnectionMessagesReachHandler() throws Exception {
        BiConsumer<String, String> handler = Mockito.mock(BiConsumer.class);
        manager.onMessage(handler);
        manager.connectNow(broker(0L));

        MqttCallback callback = manager.lastCallback;
        assertNotNull(callback, "建连时应注册 MqttCallback");
        String payload = "{\"method\":\"stick_control\",\"data\":{},\"seq\":1}";
        callback.messageArrived(expectedDrcDownTopic(),
                new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));

        verify(handler).accept(expectedDrcDownTopic(), payload);
        verify(mqtt).logTraffic("recv", expectedDrcDownTopic(), payload);
    }

    @Test
    @DisplayName("TC-DRC-074: disconnect 幂等（无连接时调用不抛异常）")
    void disconnectWithoutConnectionIsIdempotent() {
        assertDoesNotThrow(() -> manager.disconnect("关机"));
        assertFalse(manager.isActive());
    }
}
