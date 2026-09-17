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

import jakarta.annotation.PreDestroy;
import ltd.cdmi.dji.cloudapi.sdk.command.service.drc.DrcMqttBroker;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * DRC 专用 MQTT 连接管理器：按真机方式模拟 drc_mode_enter 建立的低延迟控制链路。
 *
 * <p>真机行为：收到 {@code drc_mode_enter}（携带 {@code mqtt_broker} 凭证）后，用平台下发的
 * client_id/username/password 建立<strong>独立于主连接</strong>的 DRC 专用 MQTT 连接
 * （EMQX Dashboard 可见独立 client），并在该连接上承载 {@code drc/down} 订阅与
 * {@code drc/up} 发布；{@code drc_mode_exit} 时断开。</p>
 *
 * <p><b>expire_time 语义（DJI 文档已确认，见 SDK {@link DrcMqttBroker} javadoc）</b>：
 * 认证信息过期时间，单位秒（绝对时间戳）；"认证信息过期后，并不会影响已建立连接的设备"
 * ——即到期<b>不断开</b>已建立的连接，仅凭证不可再用于新建连接。模拟器按此实现：
 * 到期不断连，仅记录凭证到期日志。</p>
 *
 * <h3>生命周期（TC-DRC-067~074）</h3>
 * <ul>
 *   <li><b>建立</b>：drc_mode_enter 时异步建连（不阻塞 services_reply 回包），成功后订阅 drc/down 并激活路由</li>
 *   <li><b>重复进入</b>：先断旧连再建新连（TC-DRC-068）</li>
 *   <li><b>退出</b>：drc_mode_exit 断开，路由回退主连接（TC-DRC-069）</li>
 *   <li><b>关机</b>：/api/offline、/api/disconnect 联动断开，无僵尸连接（TC-DRC-074）</li>
 *   <li><b>失败容错</b>：建连失败不阻断 DRC 模式，drc/down 与 drc/up 继续走主连接（TC-DRC-072）</li>
 * </ul>
 *
 * <h3>双订阅去重（TC-DRC-073）</h3>
 * <p>主连接在 connectComplete 时订阅 drc/down（既有行为）保持不变；专用连接建连后也订阅同一 topic。
 * {@link #isActive()} 为 DrcCommandHandler 的路由开关：激活期间主连接收到的 drc/down 忽略，
 * 仅专用连接的消息被处理，避免杆量指令双份积分。</p>
 */
@Component
public class DrcConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DrcConnectionManager.class);

    /** 建连超时（秒），与主连接一致（AGENTS.md：连接超时 3 秒） */
    private static final int CONNECT_TIMEOUT_SECONDS = 3;
    /** keepAlive 间隔（秒），与主连接一致 */
    private static final int KEEP_ALIVE_SECONDS = 30;

    private final MqttClientManager mqtt;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;
    private final DiagnosticLogRecorder diagnosticRecorder;

    /** DRC 专用连接（IMqttClient 接口类型，便于测试注入 mock） */
    private volatile IMqttClient drcClient;
    /** DRC 路由激活标志：true=专用连接已就绪，drc/down 由专用连接处理 */
    private volatile boolean active;
    /** 专用连接 drc/down 消息处理器（DrcCommandHandler 经 onMessage 注入，运行时设置避免构造器循环依赖） */
    private volatile java.util.function.BiConsumer<String, String> messageHandler;

    public DrcConnectionManager(MqttClientManager mqtt, RuntimeConfig runtimeConfig,
                                DockTopicSchema dockTopicSchema, DiagnosticLogRecorder diagnosticRecorder) {
        this.mqtt = mqtt;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
        this.diagnosticRecorder = diagnosticRecorder;
    }

    /**
     * 进入 DRC 模式：按平台下发的 broker 凭证异步建立专用连接（TC-DRC-067）。
     * <p>异步执行避免阻塞 MQTT 回调线程（services_reply 先行返回 result=0）。
     * broker 为 null 时（调试器等工具未携带 mqtt_broker）跳过建连，DRC 消息继续走主连接。</p>
     * @param broker 平台下发的 DRC MQTT 凭证，可为 null
     */
    public void enter(DrcMqttBroker broker) {
        if (broker == null) {
            diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "drc_mode_enter",
                    "指令未携带 mqtt_broker，跳过 DRC 专用连接建立，DRC 消息走主连接");
            return;
        }
        CompletableFuture.runAsync(() -> connectNow(broker));
    }

    /**
     * 注册专用连接的 drc/down 消息处理器（DrcCommandHandler 在 @PostConstruct 调用，运行时注入避免构造器循环依赖）。
     * @param handler 回调 (topic, payload)
     */
    public void onMessage(java.util.function.BiConsumer<String, String> handler) {
        this.messageHandler = handler;
    }

    /**
     * 同步建立专用连接（enter 的执行体，包可见供单元测试直接调用）。
     * <p>重复 enter 防御（TC-DRC-068）：同步段内先断旧连再建新连，避免异步乱序遗留僵尸连接。</p>
     */
    synchronized void connectNow(DrcMqttBroker broker) {
        disconnect("drc_mode_enter 重新进入");
        String gatewaySn = runtimeConfig.getGatewaySn();
        String drcDownTopic = dockTopicSchema.topic(dockTopicSchema.drcDown(), gatewaySn);
        try {
            String brokerUri = buildBrokerUri(broker);
            IMqttClient client = createClient(brokerUri, broker.clientId());

            MqttConnectOptions options = new MqttConnectOptions();
            // username/password 可空（SDK 放松校验后允许缺省，匿名认证场景直接跳过）
            if (broker.username() != null) {
                options.setUserName(broker.username());
            }
            if (broker.password() != null) {
                options.setPassword(broker.password().toCharArray());
            }
            options.setCleanSession(true);
            options.setAutomaticReconnect(false);
            options.setConnectionTimeout(CONNECT_TIMEOUT_SECONDS);
            options.setKeepAliveInterval(KEEP_ALIVE_SECONDS);

            client.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    // 专用连接掉线：立即回退主连接路由
                    log.warn("DRC 专用连接断开，消息回退主连接: {}", cause == null ? "unknown" : cause.getMessage());
                    DrcConnectionManager.this.disconnect("连接掉线");
                }

                @Override
                public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                    String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                    mqtt.logTraffic("recv", topic, payload);
                    java.util.function.BiConsumer<String, String> handler = messageHandler;
                    if (handler != null) {
                        handler.accept(topic, payload);
                    }
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                    // QoS 1 投递完成回调，无需处理
                }
            });

            client.connect(options);
            client.subscribe(drcDownTopic, 1);
            drcClient = client;
            active = true;
            log.info("DRC 专用连接已建立: clientId={}, broker={}, 订阅={}, 凭证到期={}",
                    broker.clientId(), brokerUri, drcDownTopic,
                    broker.expireTime() != null ? broker.expireTime() + "s（到期不断连，DJI 文档：过期不影响已建立连接）" : "未下发");
        } catch (Exception e) {
            // 建连失败不阻断 DRC 模式（TC-DRC-072）：active=false，消息继续走主连接
            active = false;
            drcClient = null;
            log.error("DRC 专用连接建立失败（DRC 消息回退主连接）: clientId={}, {}", broker.clientId(), e.getMessage(), e);
            diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "drc_mode_enter",
                    "DRC 专用连接建立失败: " + e.getMessage() + "（clientId=" + broker.clientId()
                    + "），DJI 文档未明确真机建连失败行为，模拟器不阻断 DRC 模式，消息回退主连接，待真机验证");
        }
    }

    /**
     * 断开 DRC 专用连接（drc_mode_exit / 关机 / 连接掉线共用）。
     * <p>断开后 isActive()=false，drc/down 恢复主连接处理、drc/up 回退主连接发布。</p>
     * @param reason 断开原因（日志用）
     */
    public synchronized void disconnect(String reason) {
        IMqttClient client = drcClient;
        drcClient = null;
        active = false;
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
            log.info("DRC 专用连接已断开（{}）", reason);
        } catch (MqttException e) {
            log.warn("断开 DRC 专用连接异常（{}）: {}", reason, e.getMessage());
        }
    }

    /**
     * drc/up 唯一发布入口：专用连接在线走专用连接，否则回退主连接（TC-DRC-071）。
     * @param topic drc/up 完整 topic
     * @param payload 消息内容
     */
    public void publishUp(String topic, String payload) {
        IMqttClient client = drcClient;
        if (active && client != null && client.isConnected()) {
            try {
                org.eclipse.paho.client.mqttv3.MqttMessage message =
                        new org.eclipse.paho.client.mqttv3.MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(1);
                client.publish(topic, message);
                // 流量日志统一记录（前端指令通讯窗口可见，与主连接 send 日志同源）
                mqtt.logTraffic("send", topic, payload);
                log.debug("drc/up 已从专用连接发布 topic={}", topic);
                return;
            } catch (Exception e) {
                log.warn("drc/up 从专用连接发布失败，回退主连接: {}", e.getMessage());
            }
        }
        mqtt.publish(topic, payload);
    }

    /** DRC 路由是否激活（专用连接就绪）。DrcCommandHandler 以此做双订阅去重（TC-DRC-073）。 */
    public boolean isActive() {
        return active;
    }

    /**
     * 构造 broker URI：剥离地址携带的 scheme 前缀，按 enable_tls 选拼 tcp:// 或 ssl://。
     * <p>地址规范化复用主连接逻辑（容器内 localhost → host.docker.internal）。
     * enable_tls 缺省/true 时：true 用 ssl://（依赖 JVM 默认信任库，自签名证书场景可能失败，M-2 待真机验证）。</p>
     */
    private String buildBrokerUri(DrcMqttBroker broker) {
        String host = MqttClientManager.normalizeHost(broker.address());
        String scheme = Boolean.TRUE.equals(broker.enableTls()) ? "ssl://" : "tcp://";
        return scheme + host;
    }

    /**
     * 创建 Paho 客户端（protected 便于测试注入 mock）。
     */
    protected IMqttClient createClient(String brokerUri, String clientId) throws MqttException {
        return new MqttClient(brokerUri, clientId, new MemoryPersistence());
    }

    @PreDestroy
    public void destroy() {
        disconnect("应用关闭");
    }
}
