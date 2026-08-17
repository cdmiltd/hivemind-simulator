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
import jakarta.annotation.PreDestroy;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.MessageLogStore;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MQTT 客户端管理器：连接 EMQX、订阅下行 topic、分发消息、提供发布方法。
 * <p>采用 Paho v3 客户端，开启自动重连。所有业务消息由 {@link MqttMessageListener} 监听器处理。</p>
 */
@Component
public class MqttClientManager implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttClientManager.class);

    private final MqttProperties mqttProps;
    private final SimulatorProperties props;
    private final ObjectMapper objectMapper;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;
    private final MessageLogStore messageLogStore;

    private volatile MqttClient client;

    /** 按 topic 注册的消息监听器列表（支持同一 topic 多监听器） */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MqttMessageListener>> listeners = new ConcurrentHashMap<>();

    /** 消息日志缓冲默认大小（当配置未指定时使用） */
    private static final int DEFAULT_MAX_LOG_SIZE = 2000;
    /** getLogs 返回的最大条数（仅返回最新 N 条，减少前端渲染压力） */
    private static final int MAX_RETURN_LOG_SIZE = 500;
    // 使用 ArrayDeque 而非 ArrayList：pollFirst() 是 O(1)，避免 ArrayList.remove(0) 的 O(n) 元素移动
    private final Deque<Map<String, Object>> messageLogs = new ArrayDeque<>();

    public MqttClientManager(MqttProperties mqttProps, SimulatorProperties props, ObjectMapper objectMapper, RuntimeConfig runtimeConfig, DockTopicSchema dockTopicSchema, MessageLogStore messageLogStore) {
        this.mqttProps = mqttProps;
        this.props = props;
        this.objectMapper = objectMapper;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
        this.messageLogStore = messageLogStore;
    }

    /**
     * 使用 {@link RuntimeConfig} 当前配置建立 MQTT 连接（首次连接与重连共用）。
     * @return 诊断码：null=成功；{@link DiagnosticCode#PLATFORM_AUTH_FAILED}=凭证错误；{@link DiagnosticCode#PLATFORM_HOST_UNREACHABLE}=地址不可达
     */
    private synchronized DiagnosticCode doConnect() {
        try {
            String clientId = mqttProps.simulatorClientIdPrefix() + UUID.randomUUID().toString().substring(0, 8);
            String brokerUri = "tcp://" + runtimeConfig.getMqttHost() + ":" + runtimeConfig.getMqttPort();

            client = new MqttClient(brokerUri, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(runtimeConfig.getMqttUsername());
            options.setPassword(runtimeConfig.getMqttPassword().toCharArray());
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(3);
            options.setKeepAliveInterval(30);
            // 告知 broker 本客户端的遗嘱：设备下线（QoS 1）
            // DJI 下线通过 update_topo 的空 sub_devices 列表体现，method 字段必须为 update_topo
            String willPayload = "{\"method\":\"update_topo\",\"data\":{\"type\":3,\"sub_type\":0,\"sub_devices\":[],\"thing_version\":\"" + runtimeConfig.getThingVersion() + "\"}}";
            TopicSchema schema = currentTopicSchema();
            options.setWill(
                    schema.topic(schema.status(), runtimeConfig.getGatewaySn()),
                    willPayload.getBytes(StandardCharsets.UTF_8),
                    1, false);

            client.setCallback(this);
            client.connect(options);
            log.info("MQTT 客户端已启动，clientId={}, broker={}", clientId, brokerUri);
            return null;
        } catch (MqttException e) {
            // CONNACK code 4(Bad User Name or Password) → REASON_CODE_FAILED_AUTHENTICATION
            // CONNACK code 5(Not Authorized) → REASON_CODE_NOT_AUTHORIZED
            // 两者都归类为凭证错误
            if (e.getReasonCode() == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                    || e.getReasonCode() == MqttException.REASON_CODE_NOT_AUTHORIZED) {
                log.error("MQTT 认证失败（账号或密码错误）: reasonCode={}, {}", e.getReasonCode(), e.getMessage());
                return DiagnosticCode.PLATFORM_AUTH_FAILED;
            }
            log.error("MQTT 连接失败（无法连接到 broker）: reasonCode={}, {}", e.getReasonCode(), e.getMessage(), e);
            return DiagnosticCode.PLATFORM_HOST_UNREACHABLE;
        } catch (Exception e) {
            log.error("MQTT 连接初始化失败: {}", e.getMessage(), e);
            return DiagnosticCode.PLATFORM_HOST_UNREACHABLE;
        }
    }

    /**
     * 使用新的连接配置重连：先关闭旧连接，再用 {@link RuntimeConfig} 当前值建立新连接。
     * <p>供 Web 控制台修改 MQTT 配置后调用。</p>
     * @return 诊断码：null=成功；{@link DiagnosticCode#PLATFORM_AUTH_FAILED}=凭证错误；{@link DiagnosticCode#PLATFORM_HOST_UNREACHABLE}=地址不可达
     */
    public synchronized DiagnosticCode reconnect() {
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            }
        } catch (Exception e) {
            log.warn("关闭旧 MQTT 连接异常: {}", e.getMessage());
        }
        client = null;
        return doConnect();
    }

    /**
     * 主动断开 MQTT 连接（下线时调用）。
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
            log.warn("断开 MQTT 连接异常: {}", e.getMessage());
        }
        client = null;
    }

    @PreDestroy
    public void destroy() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
            if (client != null) {
                client.close();
            }
            log.info("MQTT 客户端已关闭");
        } catch (Exception e) {
            log.warn("关闭 MQTT 客户端异常: {}", e.getMessage());
        }
    }

    // ==================== 连接回调 ====================

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        String gatewaySn = runtimeConfig.getGatewaySn();
        if (reconnect) {
            log.info("MQTT 已重连，重新订阅下行 topic");
        } else {
            log.info("MQTT 首次连接成功");
        }
        // 订阅云→设备下行 topic（QoS 1）
        // Pilot 模式网关为遥控器（controllerSn），Dock 模式网关为机场（dockSn）
        TopicSchema schema = currentTopicSchema();
        List<String> downTopics = new ArrayList<>();
        downTopics.add(dockTopicSchema.topic(dockTopicSchema.services(), gatewaySn));
        downTopics.add(dockTopicSchema.topic(dockTopicSchema.propertySet(), gatewaySn));
        downTopics.add(dockTopicSchema.topic(dockTopicSchema.eventsReply(), gatewaySn));
        downTopics.add(dockTopicSchema.topic(dockTopicSchema.requestsReply(), gatewaySn));
        downTopics.add(schema.topic(schema.statusReply(), gatewaySn));
        downTopics.add(dockTopicSchema.topic(dockTopicSchema.drcDown(), gatewaySn));
        for (String topic : downTopics) {
            try {
                client.subscribe(topic, 1);
                log.debug("已订阅: {}", topic);
            } catch (Exception e) {
                log.error("订阅失败: {} - {}", topic, e.getMessage());
            }
        }
    }

    /**
     * 获取当前模式对应的 TopicSchema。
     * <p>Pilot 模式（RC Plus 2）使用 {@link PilotTopicSchema}（status/statusReply 走 thing/product 通道），
     * 其他模式使用 {@link DockTopicSchema}（status/statusReply 走 sys/product 通道）。
     * <p>osd/state/services/drc 等通道两种模式模板一致，统一用 {@link DockTopicSchema}。
     */
    private TopicSchema currentTopicSchema() {
        if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
            return new PilotTopicSchema(runtimeConfig.getControllerType());
        }
        return dockTopicSchema;
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT 连接断开，等待自动重连: {}", cause == null ? "unknown" : cause.getMessage());
    }

    // ==================== 消息分发回调 ====================

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.debug("收到消息 topic={}, payload={}", topic, payload);
        addLog("recv", topic, payload);

        // 按 topic 精确匹配分发
        List<MqttMessageListener> topicListeners = listeners.get(topic);
        if (topicListeners != null) {
            for (MqttMessageListener listener : topicListeners) {
                try {
                    listener.onMessage(topic, payload);
                } catch (Exception e) {
                    log.error("监听器处理消息异常 topic={}: {}", topic, e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS 1/2 投递完成回调，无需处理
    }

    // ==================== 订阅注册 ====================

    /**
     * 注册消息监听器。topic 必须是完整 topic（非模板），订阅由本管理器统一管理。
     * @param topic 完整 topic
     * @param listener 监听器
     */
    public void addListener(String topic, MqttMessageListener listener) {
        listeners.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(listener);
        log.debug("已注册监听器 topic={}", topic);
    }

    // ==================== 发布方法 ====================

    /**
     * 发布消息到指定 topic（QoS 1）。
     * @param topic 完整 topic
     * @param payload 消息内容
     */
    public void publish(String topic, String payload) {
        publish(topic, payload, 1, false);
    }

    /**
     * 发布消息到指定 topic。
     * @param topic 完整 topic
     * @param payload 消息内容
     * @param qos 服务质量等级（0/1/2）
     * @param retained 是否保留消息
     */
    public void publish(String topic, String payload, int qos, boolean retained) {
        if (client == null || !client.isConnected()) {
            log.warn("MQTT 未连接，丢弃消息 topic={}", topic);
            return;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retained);
            client.publish(topic, message);
            addLog("send", topic, payload);
            log.debug("已发布 topic={}, payload={}", topic, payload);
        } catch (Exception e) {
            log.error("发布消息失败 topic={}: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * 发布 JSON 对象（自动序列化）。
     */
    public void publishJson(String topic, Object obj) {
        try {
            publish(topic, objectMapper.writeValueAsString(obj));
        } catch (Exception e) {
            log.error("JSON 序列化失败 topic={}: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * MQTT 客户端是否已连接。
     */
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * 获取日志缓冲最大条数（从配置读取，未配置则使用默认值）。
     */
    private int getMaxLogSize() {
        if (props.log() != null && props.log().maxSize() > 0) {
            return props.log().maxSize();
        }
        return DEFAULT_MAX_LOG_SIZE;
    }

    /**
     * 记录消息日志（内存缓冲 + 本地文件持久化）。
     * <p>内存缓冲超过最大条数时自动清除历史；每条消息同时写入本地 JSON Lines 文件，
     * 供前端上拉加载历史消息和下载日志文件。
     */
    private void addLog(String direction, String topic, String payload) {
        long timestamp = System.currentTimeMillis();
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        String method = "";
        try {
            JsonNode node = objectMapper.readTree(payload);
            method = node.path("method").asText("");
        } catch (Exception e) {
            // payload 非 JSON 或解析失败，method 留空
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", timestamp);
        entry.put("time", timeStr);
        entry.put("direction", direction);
        entry.put("topic", topic);
        entry.put("method", method);
        entry.put("payload", payload);

        // 写入内存缓冲（供前端快速查询最近 N 条）
        synchronized (messageLogs) {
            if (messageLogs.size() >= getMaxLogSize()) {
                messageLogs.pollFirst();  // O(1)，替代 ArrayList.remove(0) 的 O(n)
            }
            messageLogs.addLast(entry);
        }

        // 持久化到本地文件（供前端上拉加载历史 + 下载）
        messageLogStore.append(direction, topic, method, payload, timestamp);
    }

    /**
     * 从本地文件查询历史消息日志（分页）。
     * <p>返回 timestamp &lt; beforeTime 的最近 limit 条消息（正序：旧→新）。
     *
     * @param beforeTime 时间戳分界点（毫秒），null 表示从最新开始
     * @param limit      返回条数
     * @return 消息日志列表（正序）
     */
    public List<Map<String, Object>> queryHistory(Long beforeTime, int limit) {
        return messageLogStore.queryHistory(beforeTime, limit);
    }

    /**
     * 获取 {@link MessageLogStore} 实例，供 Controller 调用下载/列表功能。
     */
    public MessageLogStore getMessageLogStore() {
        return messageLogStore;
    }

    /**
     * 获取消息日志列表（供 Web 控制台查询）。
     * 仅返回最新 MAX_RETURN_LOG_SIZE 条，减少前端渲染压力。
     */
    public List<Map<String, Object>> getLogs() {
        synchronized (messageLogs) {
            if (messageLogs.size() <= MAX_RETURN_LOG_SIZE) {
                return new ArrayList<>(messageLogs);
            }
            // 从尾部倒序取最新 N 条，再反转为正序（旧→新）
            Iterator<Map<String, Object>> it = ((ArrayDeque<Map<String, Object>>) messageLogs).descendingIterator();
            List<Map<String, Object>> result = new ArrayList<>(MAX_RETURN_LOG_SIZE);
            while (it.hasNext() && result.size() < MAX_RETURN_LOG_SIZE) {
                result.add(it.next());
            }
            Collections.reverse(result);
            return result;
        }
    }

    /**
     * 清空消息日志。
     */
    public void clearLogs() {
        synchronized (messageLogs) {
            messageLogs.clear();
        }
    }

    /**
     * 消息监听器函数式接口。
     */
    @FunctionalInterface
    public interface MqttMessageListener {
        /** 收到消息时回调 */
        void onMessage(String topic, String payload);
    }
}
