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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.dji.cloudapi.sdk.protocol.topic.TopicChannel;
import ltd.cdmi.dji.cloudapi.sdk.protocol.topic.TopicTemplate;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 监控器 MQTT 客户端：作为第三方监控端连接到 DJI Cloud API 平台。
 * <p>独立于 {@link MqttClientManager}，订阅通配符 topic 监听所有设备的数据。</p>
 * <p>订阅 topic 由 SDK {@link TopicTemplate} 常量派生：将 {@code %s} 替换为 MQTT 通配符 {@code +}，
 * 即可匹配所有设备 SN。topic 格式与 SDK 定义保持一致。</p>
 */
public class MonitorMqttClient implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MonitorMqttClient.class);

    /**
     * 将 topic 模板中的 {@code %s} 占位符替换为 MQTT 单层通配符 {@code +}，
     * 用于订阅所有设备 SN 的同名通道。
     */
    private static String wildcard(String template) {
        return template.replace("%s", "+");
    }

    /** 监控器订阅的通配符 topic（匹配所有设备 SN），由 SDK TopicTemplate 常量派生 */
    private static final String[] SUBSCRIBE_TOPICS = {
        // 上行（设备→云）
        wildcard(TopicTemplate.STATUS),           // 设备上下线（update_topo，机场上云用 sys/product）
        wildcard(TopicTemplate.OSD),              // OSD 遥测数据
        wildcard(TopicTemplate.STATE),            // 状态变更
        wildcard(TopicTemplate.DRC_UP),           // DRC 上行通道（DRC 模式状态推送）
        wildcard(TopicTemplate.EVENTS),           // 事件上报
        wildcard(TopicTemplate.REQUESTS),         // 设备请求
        wildcard(TopicTemplate.SERVICES_REPLY),   // 服务指令回复
        wildcard(TopicTemplate.PROPERTY_SET_REPLY), // 属性设置回复
        // 下行（云→设备）
        wildcard(TopicTemplate.STATUS_REPLY),     // 拓扑回复（机场上云用 sys/product）
        wildcard(TopicTemplate.SERVICES),         // 服务指令下发
        wildcard(TopicTemplate.PROPERTY_SET),     // 属性设置下发
        wildcard(TopicTemplate.EVENTS_REPLY),     // 事件回复
        wildcard(TopicTemplate.REQUESTS_REPLY),   // 请求回复
    };

    private final ObjectMapper objectMapper;
    private final MessageHandler messageHandler;

    private volatile MqttClient client;

    /** 消息日志缓冲默认大小（当未传入配置时使用） */
    private static final int DEFAULT_MAX_LOG_SIZE = 2000;
    /** getLogs 返回的最大条数（仅返回最新 N 条，减少前端渲染压力） */
    private static final int MAX_RETURN_LOG_SIZE = 500;
    private final int maxLogSize;
    // 使用 ArrayDeque 而非 ArrayList：removeFirst()/pollFirst() 是 O(1)，避免 ArrayList.remove(0) 的 O(n) 元素移动
    private final Deque<Map<String, Object>> messageLogs = new ArrayDeque<>();

    /** 消息处理器接口 */
    @FunctionalInterface
    public interface MessageHandler {
        void onMessage(String topic, String payload);
    }

    public MonitorMqttClient(ObjectMapper objectMapper, MessageHandler messageHandler, int maxLogSize) {
        this.objectMapper = objectMapper;
        this.messageHandler = messageHandler;
        this.maxLogSize = maxLogSize > 0 ? maxLogSize : DEFAULT_MAX_LOG_SIZE;
    }

    /**
     * 连接到 MQTT Broker。
     * @param host broker 地址
     * @param port broker 端口
     * @param username 用户名（可为空）
     * @param password 密码（可为空）
     * @param clientIdPrefix 客户端 ID 前缀（来自配置 mqtt.monitor-client-id-prefix）
     * @return 诊断码：null=成功；{@link DiagnosticCode#PLATFORM_AUTH_FAILED}=凭证错误；{@link DiagnosticCode#PLATFORM_HOST_UNREACHABLE}=地址不可达
     */
    public synchronized DiagnosticCode connect(String host, int port, String username, String password, String clientIdPrefix) {
        try {
            String prefix = (clientIdPrefix != null && !clientIdPrefix.isEmpty()) ? clientIdPrefix : "monitor-";
            String clientId = prefix + UUID.randomUUID().toString().substring(0, 8);
            // 复用 MqttClientManager.normalizeHost：剥离协议前缀(tcp:// 等,TC-MQTT-010)+容器内 loopback 映射(TC-MQTT-011)
            // 与模拟器主连接行为完全一致，用户在监控器与模拟器填写相同地址即可连接同一 Broker
            String normalizedHost = MqttClientManager.normalizeHost(host);
            String brokerUri = "tcp://" + normalizedHost + ":" + port;

            client = new MqttClient(brokerUri, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            if (username != null && !username.isEmpty()) {
                options.setUserName(username);
            }
            if (password != null && !password.isEmpty()) {
                options.setPassword(password.toCharArray());
            }
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(3);
            options.setKeepAliveInterval(30);

            client.setCallback(this);
            client.connect(options);
            log.info("监控器 MQTT 已连接，clientId={}, broker={}", clientId, brokerUri);
            return null;
        } catch (MqttException e) {
            if (e.getReasonCode() == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                    || e.getReasonCode() == MqttException.REASON_CODE_NOT_AUTHORIZED) {
                log.error("监控器 MQTT 认证失败: reasonCode={}, {}", e.getReasonCode(), e.getMessage());
                return DiagnosticCode.PLATFORM_AUTH_FAILED;
            }
            log.error("监控器 MQTT 连接失败（无法连接到 broker）: reasonCode={}, {}", e.getReasonCode(), e.getMessage(), e);
            return DiagnosticCode.PLATFORM_HOST_UNREACHABLE;
        } catch (Exception e) {
            log.error("监控器 MQTT 连接初始化失败: {}", e.getMessage());
            return DiagnosticCode.PLATFORM_HOST_UNREACHABLE;
        }
    }

    /**
     * 断开 MQTT 连接。
     */
    public synchronized void disconnect() {
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            }
        } catch (Exception e) {
            log.warn("监控器 MQTT 断开异常: {}", e.getMessage());
        }
        client = null;
        log.info("监控器 MQTT 已断开");
    }

    /**
     * 发布消息到指定 topic。
     */
    public void publish(String topic, String payload) {
        if (client == null || !client.isConnected()) {
            log.warn("监控器 MQTT 未连接，丢弃消息 topic={}", topic);
            return;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            client.publish(topic, message);
            // 不在此处记录日志：MQTT 回环会触发 messageArrived，统一在那里根据 topic 方向记录
            log.debug("监控器已发布 topic={}", topic);
        } catch (Exception e) {
            log.error("监控器发布消息失败 topic={}: {}", topic, e.getMessage());
        }
    }

    /**
     * 连接状态唯一真相源：直接委托 Paho 实际状态（TC-MQTT-015）。
     * <p>不维护自定义连接标志——自动重连（connectComplete）后 Paho 状态自动恢复，
     * 双状态源在任何重连场景都可能不同步（曾导致"显示未连接但消息正常接收"）。</p>
     */
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    // ==================== MQTT 回调 ====================

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (reconnect) {
            log.info("监控器 MQTT 已重连，重新订阅 topic");
        } else {
            log.info("监控器 MQTT 首次连接成功");
        }
        for (String topic : SUBSCRIBE_TOPICS) {
            try {
                client.subscribe(topic, 1);
                log.debug("监控器已订阅: {}", topic);
            } catch (Exception e) {
                log.error("监控器订阅失败: {} - {}", topic, e.getMessage());
            }
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        // 不修改任何状态标志：isConnected() 委托 Paho 实际状态（TC-MQTT-015），
        // 断开时 Paho 自动置 false，自动重连成功后自动恢复 true
        log.warn("监控器 MQTT 连接断开，等待自动重连: {}", cause == null ? "unknown" : cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.debug("监控器收到消息 topic={}", topic);
        // 根据 topic 方向推断消息方向（上行=recv，下行=send），
        // 监控器自己发布的消息也会通过 MQTT 回环到达此处，统一由 topic 推断方向
        addLog(inferDirection(topic), topic, payload);
        if (messageHandler != null) {
            try {
                messageHandler.onMessage(topic, payload);
            } catch (Exception e) {
                log.error("监控器消息处理异常 topic={}: {}", topic, e.getMessage());
            }
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    // ==================== 日志 ====================

    /**
     * 根据 topic 推断消息方向（监控器/平台视角）。
     * <ul>
     *   <li>下行 topic（平台 → 设备）：services / property/set / events_reply / requests_reply / status_reply → "send"</li>
     *   <li>上行 topic（设备 → 平台）：osd / state / events / requests / services_reply / property/set_reply / status / drc/up → "recv"</li>
     * </ul>
     */
    private String inferDirection(String topic) {
        // 下行通道（平台 → 设备）：services / property/set / events_reply / requests_reply / status_reply
        // 使用 SDK TopicChannel.suffix() 常量保证与协议定义一致
        if (topic.endsWith("/" + TopicChannel.SERVICES.suffix())
                || topic.endsWith("/" + TopicChannel.PROPERTY_SET.suffix())
                || topic.endsWith("/" + TopicChannel.EVENTS_REPLY.suffix())
                || topic.endsWith("/" + TopicChannel.REQUESTS_REPLY.suffix())
                || topic.endsWith("/" + TopicChannel.STATUS_REPLY.suffix())) {
            return "send"; // 平台 → 设备
        }
        return "recv"; // 设备 → 平台
    }

    public List<Map<String, Object>> getLogs() {
        synchronized (messageLogs) {
            // 仅返回最新 MAX_RETURN_LOG_SIZE 条，减少前端渲染压力
            if (messageLogs.size() <= MAX_RETURN_LOG_SIZE) {
                return new ArrayList<>(messageLogs);
            }
            // 从尾部倒序取最新 N 条，再反转为正序（旧→新）
            Iterator<Map<String, Object>> it = ((ArrayDeque<Map<String, Object>>) messageLogs).descendingIterator();
            List<Map<String, Object>> result = new ArrayList<>(MAX_RETURN_LOG_SIZE);
            while (it.hasNext() && result.size() < MAX_RETURN_LOG_SIZE) {
                result.add(it.next());
            }
            java.util.Collections.reverse(result);
            return result;
        }
    }

    public void clearLogs() {
        synchronized (messageLogs) {
            messageLogs.clear();
        }
    }

    private void addLog(String direction, String topic, String payload) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));
        entry.put("direction", direction);
        entry.put("topic", topic);
        try {
            JsonNode node = objectMapper.readTree(payload);
            entry.put("method", node.path("method").asText(""));
        } catch (Exception e) {
            entry.put("method", "");
        }
        entry.put("payload", payload);
        synchronized (messageLogs) {
            if (messageLogs.size() >= maxLogSize) {
                messageLogs.pollFirst();  // O(1)，替代 ArrayList.remove(0) 的 O(n)
            }
            messageLogs.addLast(entry);
        }
    }
}
