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

import java.util.Set;

/**
 * WebSocket 推送消息处理器接口。
 * <p>hivemind 通过 WebSocket 推送的消息结构为 {@code {biz_code, version, timestamp, data}}，
 * 按 {@code biz_code} 路由到对应 Handler 处理。
 * <p>实现类通过 {@link #supportedBizCodes()} 声明支持的消息类型，
 * {@link HivemindWsClient} 收到消息后按 biz_code 分发。
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/websocket/map-elements/message-push.html">
 * DJI Pilot WebSocket 消息发布</a>
 */
public interface WsMessageHandler {

    /**
     * 声明本 Handler 支持处理的 biz_code 集合。
     * <p>{@link HivemindWsClient} 收到消息后，按 biz_code 匹配注册的 Handler。
     *
     * @return 支持的 biz_code 集合（不可为空）
     */
    Set<String> supportedBizCodes();

    /**
     * 处理 WebSocket 推送消息。
     * <p>消息为完整的 JSON 根节点，包含 {@code biz_code/version/timestamp/data} 字段。
     * 实现类自行解析 {@code data} 字段。
     *
     * @param message 完整的 WebSocket 推送消息（JSON 根节点）
     */
    void handle(JsonNode message);
}
