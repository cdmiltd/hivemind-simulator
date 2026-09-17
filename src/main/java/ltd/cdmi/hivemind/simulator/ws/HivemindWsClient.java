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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * hivemind WebSocket 客户端。
 * <p>Pilot 上云除 MQTT/HTTP 外，还通过 WebSocket 接收 hivemind 推送（地图元素变更、态势感知等）。
 * <p>本类仅负责连接管理和消息分发，按 {@code biz_code} 路由到注册的 {@link WsMessageHandler}。
 * 具体业务处理（如事件日志维护、HTTP 拉取触发）由各 Handler 实现。
 * <p>连接 URL 格式：{@code wss://host:port?x-auth-token=<token>}（参考 DJI JSBridge WS 模块）。
 * token 从 {@link RuntimeConfig} 读取（yml 默认或前端覆盖），按 DJI 文档要求做 URLEncode。
 * <p>ws-url 从 {@link RuntimeConfig} 读取，支持运行时覆盖。
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/jsbridge.html">
 * DJI JSBridge WS 模块</a>
 */
@Component
public class HivemindWsClient {

    private static final Logger log = LoggerFactory.getLogger(HivemindWsClient.class);

    private final RuntimeConfig runtimeConfig;
    private final ObjectMapper objectMapper;
    /** biz_code → Handler 映射（构造时一次性构建，运行时只读） */
    private final Map<String, WsMessageHandler> handlerByBizCode = new ConcurrentHashMap<>();

    private final WebSocketClient client;
    private volatile WebSocketSession session;
    private volatile boolean connected;

    public HivemindWsClient(RuntimeConfig runtimeConfig, ObjectMapper objectMapper,
                            List<WsMessageHandler> handlers) {
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
        this.client = new StandardWebSocketClient();
        // 构建 biz_code → handler 映射
        for (WsMessageHandler handler : handlers) {
            for (String bizCode : handler.supportedBizCodes()) {
                handlerByBizCode.put(bizCode, handler);
            }
        }
        log.info("HivemindWsClient 初始化完成，已注册 {} 个 biz_code: {}", handlerByBizCode.size(), handlerByBizCode.keySet());
    }

    /**
     * 建立 WebSocket 连接。
     * <p>由 {@link ltd.cdmi.hivemind.simulator.handler.MapElementSimulator#init()} 在 Pilot 上线时调用。
     * <p>连接是异步的，连接成功后通过回调更新 {@link #isConnected()} 状态。
     */
    public synchronized void connect() {
        String wsUrl = runtimeConfig.getHivemindWsUrl();
        if (wsUrl == null || wsUrl.isBlank()) {
            log.warn("hivemind WebSocket url 未配置，跳过连接");
            return;
        }
        if (connected && session != null && session.isOpen()) {
            log.debug("WebSocket 已连接，跳过重复连接");
            return;
        }
        // x-auth-token: 从 RuntimeConfig 读取（yml 默认或前端覆盖）
        // DJI 文档要求 WebSocket 连接 URL 中的 token 需 URLEncode
        String token = runtimeConfig.getHivemindWsToken();
        String encodedToken = URLEncoder.encode(token != null ? token : "", StandardCharsets.UTF_8);
        String fullUrl = wsUrl + "?x-auth-token=" + encodedToken;
        try {
            WebSocketHandler handler = new HivemindWsHandler();
            CompletableFuture<WebSocketSession> future = client.execute(handler, fullUrl);
            future.whenComplete((s, ex) -> {
                if (ex != null) {
                    connected = false;
                    log.error("WebSocket 连接失败: {}", ex.getMessage());
                }
                // 连接成功的状态更新在 afterConnectionEstablished 回调中处理
            });
            log.info("WebSocket 连接请求已发起: {}", fullUrl);
        } catch (Exception e) {
            log.error("WebSocket 连接初始化失败: {}", e.getMessage(), e);
            connected = false;
        }
    }

    /**
     * 断开 WebSocket 连接。
     * <p>由 {@link ltd.cdmi.hivemind.simulator.handler.MapElementSimulator#destroy()} 在 Pilot 下线时调用。
     */
    public synchronized void disconnect() {
        if (session != null && session.isOpen()) {
            try {
                session.close();
                log.info("WebSocket 连接已关闭");
            } catch (Exception e) {
                log.warn("关闭 WebSocket 异常: {}", e.getMessage());
            }
        }
        session = null;
        connected = false;
    }

    /** 连接是否已建立 */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 处理收到的 WebSocket 消息（按 biz_code 分发到注册的 Handler）。
     * <p>package-private，供内部回调调用和单元测试直接调用（绕过真实 WebSocket 连接）。
     */
    void dispatchMessage(String payload) {
        try {
            JsonNode message = objectMapper.readTree(payload);
            String bizCode = message.path("biz_code").asText("");
            if (bizCode.isEmpty()) {
                log.warn("WebSocket 消息缺少 biz_code: {}", payload);
                return;
            }
            WsMessageHandler handler = handlerByBizCode.get(bizCode);
            if (handler == null) {
                log.warn("WebSocket 消息未注册 Handler，biz_code={}: {}", bizCode, payload);
                return;
            }
            handler.handle(message);
        } catch (Exception e) {
            log.error("WebSocket 消息解析失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 内部 WebSocket 消息处理器。
     * <p>将 Spring WebSocket 回调委托给 {@link HivemindWsClient} 的方法。
     */
    private class HivemindWsHandler extends TextWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            HivemindWsClient.this.session = session;
            HivemindWsClient.this.connected = true;
            log.info("WebSocket 连接已建立");
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            dispatchMessage(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            HivemindWsClient.this.session = null;
            HivemindWsClient.this.connected = false;
            log.info("WebSocket 连接关闭: status={}", status);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("WebSocket 传输错误: {}", exception.getMessage());
            HivemindWsClient.this.connected = false;
        }
    }
}
