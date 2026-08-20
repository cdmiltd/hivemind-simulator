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

package ltd.cdmi.hivemind.simulator.ws.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.dji.cloudapi.sdk.websocket.WsBizCode;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import ltd.cdmi.hivemind.simulator.http.api.MapElementApi;
import ltd.cdmi.hivemind.simulator.ws.WsMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
import java.util.Set;

/**
 * 地图元素 WebSocket 消息处理器。
 * <p>处理 hivemind 推送的 4 种地图元素 biz_code：
 * <ul>
 *   <li>{@code map_group_refresh} — 图层刷新，解析 {@code data.ids[]} 并对每个 group_id 调用 HTTP 拉取元素列表</li>
 *   <li>{@code map_element_create} — 元素新增通知，仅记录事件日志</li>
 *   <li>{@code map_element_update} — 元素更新通知，仅记录事件日志</li>
 *   <li>{@code map_element_delete} — 元素删除通知，仅记录事件日志</li>
 * </ul>
 * <p>事件日志供前端通过 REST API {@code GET /api/map/ws-events} 查询，验证 WebSocket 推送是否正常。
 * <p>容量上限复用 {@code simulator.log.max-size}（默认 2000），与 {@code MqttClientManager.messageLogs} 一致。
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/websocket/map-elements/message-push.html">
 * DJI 地图元素 WebSocket 消息发布</a>
 */
@Component
public class MapElementWsHandler implements WsMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MapElementWsHandler.class);

    private static final Set<String> BIZ_CODES = Set.of(
            WsBizCode.MAP_ELEMENT_CREATE.code(),
            WsBizCode.MAP_ELEMENT_UPDATE.code(),
            WsBizCode.MAP_ELEMENT_DELETE.code(),
            WsBizCode.MAP_GROUP_REFRESH.code()
    );

    private static final int DEFAULT_MAX_LOG_SIZE = 2000;
    private static final int MAX_RETURN_EVENT_SIZE = 500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final MapElementApi mapElementApi;
    private final SimulatorProperties props;
    private final ObjectMapper objectMapper;

    /** 事件日志缓冲（FIFO，超过上限自动丢弃最旧条目） */
    private final Deque<Map<String, Object>> eventLogs = new ArrayDeque<>();

    public MapElementWsHandler(MapElementApi mapElementApi,
                               SimulatorProperties props,
                               ObjectMapper objectMapper) {
        this.mapElementApi = mapElementApi;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> supportedBizCodes() {
        return BIZ_CODES;
    }

    @Override
    public void handle(JsonNode message) {
        String bizCode = message.path("biz_code").asText("");
        JsonNode data = message.path("data");

        // map_group_refresh 触发 HTTP 拉取图层元素列表
        if (WsBizCode.MAP_GROUP_REFRESH.code().equals(bizCode)) {
            handleGroupRefresh(data);
        }

        // 所有 biz_code 都记录事件日志（供前端查询验证）
        recordEvent(bizCode, data);
    }

    /**
     * 处理图层刷新推送：对每个 group_id 触发 HTTP 拉取元素列表。
     * <p>DJI 时序图：web 端拖动地图元素 → websocket 通知客户端刷新 → 客户端 HTTP 调用获取元素列表。
     */
    private void handleGroupRefresh(JsonNode data) {
        JsonNode ids = data.path("ids");
        if (!ids.isArray() || ids.isEmpty()) {
            log.warn("map_group_refresh 推送缺少 ids 数组: {}", data);
            return;
        }
        for (JsonNode idNode : ids) {
            String groupId = idNode.asText("");
            if (groupId.isEmpty()) {
                continue;
            }
            log.info("收到图层刷新推送，触发 HTTP 拉取: group_id={}", groupId);
            HivemindHttpClient.HivemindResponse resp = mapElementApi.getElements(groupId);
            log.info("图层刷新拉取完成: group_id={} success={} code={}", groupId, resp.success(), resp.code());
        }
    }

    /**
     * 记录事件日志。
     * <p>字段：time, biz_code, group_id, element_id, name, payload
     * <p>map_group_refresh 的 group_id 字段记录 ids 逗号拼接值，其他 biz_code 记录单个 group_id。
     */
    private void recordEvent(String bizCode, JsonNode data) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalDateTime.now().format(TIME_FMT));
        entry.put("biz_code", bizCode);

        if (WsBizCode.MAP_GROUP_REFRESH.code().equals(bizCode)) {
            // map_group_refresh 的 data 结构: {ids: [group_id1, group_id2]}
            JsonNode ids = data.path("ids");
            StringBuilder sb = new StringBuilder();
            if (ids.isArray()) {
                for (int i = 0; i < ids.size(); i++) {
                    if (i > 0) {
                        sb.append(",");
                    }
                    sb.append(ids.get(i).asText());
                }
            }
            entry.put("group_id", sb.toString());
            entry.put("element_id", "");
            entry.put("name", "");
        } else {
            // map_element_create/update/delete 的 data 结构: {id, group_id, name, resource}
            entry.put("group_id", data.path("group_id").asText(""));
            entry.put("element_id", data.path("id").asText(""));
            entry.put("name", data.path("name").asText(""));
        }
        entry.put("payload", data.toString());

        synchronized (eventLogs) {
            int maxSize = getMaxLogSize();
            while (eventLogs.size() >= maxSize) {
                eventLogs.pollFirst();
            }
            eventLogs.addLast(entry);
        }
    }

    /**
     * 获取事件日志列表（供 Web 控制台查询）。
     * <p>仅返回最新 {@link #MAX_RETURN_EVENT_SIZE} 条，减少前端渲染压力。
     */
    public List<Map<String, Object>> getEvents() {
        synchronized (eventLogs) {
            if (eventLogs.size() <= MAX_RETURN_EVENT_SIZE) {
                return new ArrayList<>(eventLogs);
            }
            // 从尾部倒序取最新 N 条，再反转为正序（旧→新）
            List<Map<String, Object>> result = new ArrayList<>(MAX_RETURN_EVENT_SIZE);
            Iterator<Map<String, Object>> it = eventLogs.descendingIterator();
            while (it.hasNext() && result.size() < MAX_RETURN_EVENT_SIZE) {
                result.add(it.next());
            }
            Collections.reverse(result);
            return result;
        }
    }

    /** 事件日志总数（不受 MAX_RETURN_EVENT_SIZE 限制） */
    public int getEventCount() {
        synchronized (eventLogs) {
            return eventLogs.size();
        }
    }

    /** 清空事件日志 */
    public void clearEvents() {
        synchronized (eventLogs) {
            eventLogs.clear();
        }
    }

    private int getMaxLogSize() {
        SimulatorProperties.Log logConfig = props.log();
        return logConfig != null && logConfig.maxSize() > 0 ? logConfig.maxSize() : DEFAULT_MAX_LOG_SIZE;
    }
}
