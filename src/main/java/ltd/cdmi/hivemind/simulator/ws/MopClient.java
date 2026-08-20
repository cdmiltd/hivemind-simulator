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
import java.util.concurrent.CompletableFuture;

/**
 * MOP（Mission Open Platform）WebSocket 客户端，对应 JSBridge mop 模块。
 * <p>Pilot 通过 MOP WebSocket 通道进行通用数据传输（如自定义业务数据上下行）。
 * <p>本类仅负责连接管理：建立/断开连接、发送数据、记录接收到的消息。
 * MOP 是通用数据通道，模拟器不处理具体业务，收到消息仅记录日志。
 * <p>连接 URL 格式：{@code wss://host?x-auth-token=<urlencoded_token>}（参考 DJI JSBridge mop 模块）。
 * host/token 从 {@link RuntimeConfig} 读取（yml 默认或前端覆盖），按 DJI 文档要求 token 需 URLEncode。
 * <p>设计参考 {@link HivemindWsClient} 但更简单：不需要 biz_code 分发，只记录日志。
 */
@Component
public class MopClient {

    private static final Logger log = LoggerFactory.getLogger(MopClient.class);

    private final RuntimeConfig runtimeConfig;
    private final WebSocketClient client;
    private volatile WebSocketSession session;
    private volatile boolean connected;

    public MopClient(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
        this.client = new StandardWebSocketClient();
    }

    /**
     * 建立 MOP WebSocket 连接。
     * <p>host 未配置（空）时跳过连接。
     * <p>连接是异步的，连接成功后通过回调更新 {@link #isConnected()} 状态。
     */
    public synchronized void connect() {
        String host = runtimeConfig.getMopHost();
        if (host == null || host.isBlank()) {
            log.warn("MOP host 未配置，跳过连接");
            return;
        }
        if (connected && session != null && session.isOpen()) {
            log.debug("MOP 已连接，跳过重复连接");
            return;
        }
        // x-auth-token: 从 RuntimeConfig 读取（yml 默认或前端覆盖）
        // DJI 文档要求 WebSocket 连接 URL 中的 token 需 URLEncode
        String token = runtimeConfig.getMopToken();
        String encodedToken = URLEncoder.encode(token != null ? token : "", StandardCharsets.UTF_8);
        String fullUrl = host + "?x-auth-token=" + encodedToken;
        try {
            WebSocketHandler handler = new MopWsHandler();
            CompletableFuture<WebSocketSession> future = client.execute(handler, fullUrl);
            future.whenComplete((s, ex) -> {
                if (ex != null) {
                    connected = false;
                    log.error("MOP 连接失败: {}", ex.getMessage());
                }
                // 连接成功的状态更新在 afterConnectionEstablished 回调中处理
            });
            log.info("MOP 连接请求已发起: {}", fullUrl);
        } catch (Exception e) {
            log.error("MOP 连接初始化失败: {}", e.getMessage(), e);
            connected = false;
        }
    }

    /** 断开 MOP WebSocket 连接。 */
    public synchronized void disconnect() {
        if (session != null && session.isOpen()) {
            try {
                session.close();
                log.info("MOP 连接已关闭");
            } catch (Exception e) {
                log.warn("关闭 MOP 异常: {}", e.getMessage());
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
     * 通过 MOP 通道发送数据。
     *
     * @param message 待发送的文本数据
     * @return true=发送成功，false=未连接或发送异常
     */
    public synchronized boolean sendMessage(String message) {
        if (!connected || session == null || !session.isOpen()) {
            log.warn("MOP 未连接，无法发送消息");
            return false;
        }
        try {
            session.sendMessage(new TextMessage(message));
            return true;
        } catch (Exception e) {
            log.error("MOP 发送消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 内部 WebSocket 消息处理器。
     * <p>MOP 是通用数据通道，模拟器不处理具体业务，收到消息仅记录日志。
     */
    private class MopWsHandler extends TextWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            MopClient.this.session = session;
            MopClient.this.connected = true;
            log.info("MOP 连接已建立");
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            // MOP 是通用数据通道，模拟器不处理具体业务，仅记录日志
            log.debug("MOP 收到消息: {}", message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            MopClient.this.session = null;
            MopClient.this.connected = false;
            log.info("MOP 连接关闭: status={}", status);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("MOP 传输错误: {}", exception.getMessage());
            MopClient.this.connected = false;
        }
    }
}
