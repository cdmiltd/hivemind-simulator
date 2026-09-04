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
import ltd.cdmi.hivemind.simulator.http.api.DeviceTopoApi;
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
 * 态势感知 WebSocket 消息处理器。
 * <p>处理 hivemind 推送的 4 种态势感知 biz_code：
 * <ul>
 *   <li>{@code device_osd} — 设备遥感信息（定频推送），仅记录事件日志</li>
 *   <li>{@code device_online} — 设备上线，触发"获取设备拓扑列表"HTTP 调用 + 记录事件日志</li>
 *   <li>{@code device_offline} — 设备下线，触发"获取设备拓扑列表"HTTP 调用 + 记录事件日志</li>
 *   <li>{@code device_update_topo} — 设备拓扑更新，触发"获取设备拓扑列表"HTTP 调用 + 记录事件日志</li>
 * </ul>
 * <p>事件日志供前端通过 REST API {@code GET /api/tsa/ws-events} 查询，验证 WebSocket 推送是否正常。
 * <p>容量上限复用 {@code simulator.log.max-size}（默认 2000），与 {@code MapElementWsHandler.eventLogs} 一致。
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/websocket/situation-awareness/message-push.html">
 * DJI 态势感知 WebSocket 消息发布</a>
 */
@Component
public class SituationAwarenessWsHandler implements WsMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(SituationAwarenessWsHandler.class);

    private static final Set<String> BIZ_CODES = Set.of(
            WsBizCode.DEVICE_OSD.code(),
            WsBizCode.DEVICE_ONLINE.code(),
            WsBizCode.DEVICE_OFFLINE.code(),
            WsBizCode.DEVICE_UPDATE_TOPO.code()
    );

    /** 触发"获取设备拓扑列表"的 biz_code 集合 */
    private static final Set<String> TOPO_TRIGGER_CODES = Set.of(
            WsBizCode.DEVICE_ONLINE.code(),
            WsBizCode.DEVICE_OFFLINE.code(),
            WsBizCode.DEVICE_UPDATE_TOPO.code()
    );

    private static final int DEFAULT_MAX_LOG_SIZE = 2000;
    private static final int MAX_RETURN_EVENT_SIZE = 500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final DeviceTopoApi deviceTopoApi;
    private final SimulatorProperties props;
    private final ObjectMapper objectMapper;

    /** 事件日志缓冲（FIFO，超过上限自动丢弃最旧条目） */
    private final Deque<Map<String, Object>> eventLogs = new ArrayDeque<>();

    public SituationAwarenessWsHandler(DeviceTopoApi deviceTopoApi,
                                       SimulatorProperties props,
                                       ObjectMapper objectMapper) {
        this.deviceTopoApi = deviceTopoApi;
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

        // device_online/offline/update_topo 触发"获取设备拓扑列表"HTTP 调用
        if (TOPO_TRIGGER_CODES.contains(bizCode)) {
            triggerDeviceTopoFetch(bizCode);
        }

        // 所有 biz_code 都记录事件日志（供前端查询验证）
        recordEvent(bizCode, data);
    }

    /**
     * 触发"获取设备拓扑列表"HTTP 调用。
     * <p>DJI 文档：PILOT 收到 device_online/offline/update_topo 推送后，会触发获取设备拓扑列表。
     */
    private void triggerDeviceTopoFetch(String bizCode) {
        log.info("收到 {} 推送，触发获取设备拓扑列表", bizCode);
        HivemindHttpClient.HivemindResponse resp = deviceTopoApi.getDeviceTopo();
        log.info("获取设备拓扑列表完成: biz_code={} success={} code={}", bizCode, resp.success(), resp.code());
    }

    /**
     * 记录事件日志。
     * <p>device_osd 字段：time/biz_code/sn/latitude/longitude/height/attitude_head/elevation/horizontal_speed/vertical_speed/payload
     * <p>其他 biz_code 字段：time/biz_code/payload（data 为空对象）
     */
    private void recordEvent(String bizCode, JsonNode data) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalDateTime.now().format(TIME_FMT));
        entry.put("biz_code", bizCode);

        if (WsBizCode.DEVICE_OSD.code().equals(bizCode)) {
            // device_osd 的 data 结构: {sn, host: {latitude, longitude, height, ...}}
            JsonNode host = data.path("host");
            entry.put("sn", data.path("sn").asText(""));
            entry.put("latitude", host.path("latitude").asDouble(0));
            entry.put("longitude", host.path("longitude").asDouble(0));
            entry.put("height", host.path("height").asDouble(0));
            entry.put("attitude_head", host.path("attitude_head").asDouble(0));
            entry.put("elevation", host.path("elevation").asDouble(0));
            entry.put("horizontal_speed", host.path("horizontal_speed").asDouble(0));
            entry.put("vertical_speed", host.path("vertical_speed").asDouble(0));
        }
        // device_online/offline/update_topo 的 data 为空对象，仅记录 payload
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
