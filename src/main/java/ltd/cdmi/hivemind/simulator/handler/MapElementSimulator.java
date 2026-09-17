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

package ltd.cdmi.hivemind.simulator.handler;

import com.fasterxml.jackson.databind.JsonNode;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import ltd.cdmi.hivemind.simulator.http.api.MapElementApi;
import ltd.cdmi.hivemind.simulator.ws.HivemindWsClient;
import ltd.cdmi.hivemind.simulator.ws.handler.MapElementWsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 地图元素模拟器。
 * <p>协调 HTTP 客户端和 WebSocket 客户端，管理地图元素模拟生命周期。
 * <p>Pilot 上线后：
 * <ul>
 *   <li>自动拉取全部地图元素列表（DJI 时序图要求）</li>
 *   <li>建立 WebSocket 连接，接收 hivemind 推送的地图元素变更通知</li>
 * </ul>
 * <p>Pilot 下线时断开 WebSocket 连接（事件日志保留，供前端查看历史）。
 * <p>提供 REST API 供前端触发创建/更新/删除元素，以及查询 WebSocket 推送事件历史。
 * <p>仅 Pilot 模式激活。</p>
 */
@Component
public class MapElementSimulator {

    private static final Logger log = LoggerFactory.getLogger(MapElementSimulator.class);

    private final MapElementApi mapElementApi;
    private final RuntimeConfig runtimeConfig;
    private final HivemindWsClient wsClient;
    private final MapElementWsHandler wsHandler;

    public MapElementSimulator(MapElementApi mapElementApi, RuntimeConfig runtimeConfig,
                               HivemindWsClient wsClient, MapElementWsHandler wsHandler) {
        this.mapElementApi = mapElementApi;
        this.runtimeConfig = runtimeConfig;
        this.wsClient = wsClient;
        this.wsHandler = wsHandler;
    }

    /**
     * Pilot 上线后初始化：拉取全部地图元素列表 + 建立 WebSocket 连接。
     * <p>由 PilotOnlineService 在 update_topo 成功后调用。</p>
     */
    public void init() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return;
        }
        log.info("地图元素模拟器初始化: 拉取全部地图元素列表 + 建立 WebSocket 连接");
        fetchAllElements();
        wsClient.connect();
    }

    /**
     * Pilot 下线时清理：断开 WebSocket 连接。
     * <p>由 PilotOnlineService 在下线时调用。
     * <p>事件日志不清空，保留历史供前端查看。</p>
     */
    public void destroy() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return;
        }
        log.info("地图元素模拟器清理: 断开 WebSocket 连接");
        wsClient.disconnect();
    }

    /** 拉取全部地图元素列表 */
    public HivemindHttpClient.HivemindResponse fetchAllElements() {
        return mapElementApi.getElements(null);
    }

    /** 拉取指定图层的地图元素列表（WebSocket 刷新图层推送时调用） */
    public HivemindHttpClient.HivemindResponse fetchElements(String groupId) {
        return mapElementApi.getElements(groupId);
    }

    /** 创建地图元素 */
    public HivemindHttpClient.HivemindResponse createElement(String groupId, JsonNode body) {
        if (!isPilotMode()) {
            return notPilotMode();
        }
        return mapElementApi.createElement(groupId, body);
    }

    /** 更新地图元素 */
    public HivemindHttpClient.HivemindResponse updateElement(String elementId, JsonNode body) {
        if (!isPilotMode()) {
            return notPilotMode();
        }
        return mapElementApi.updateElement(elementId, body);
    }

    /** 删除地图元素 */
    public HivemindHttpClient.HivemindResponse deleteElement(String elementId) {
        if (!isPilotMode()) {
            return notPilotMode();
        }
        return mapElementApi.deleteElement(elementId);
    }

    /** WebSocket 连接是否已建立 */
    public boolean isWsConnected() {
        return wsClient.isConnected();
    }

    /**
     * 获取 WebSocket 推送事件历史（供 Web 控制台查询）。
     * <p>返回最新 500 条，每条含 time/biz_code/group_id/element_id/name/payload。
     */
    public List<Map<String, Object>> getWsEvents() {
        return wsHandler.getEvents();
    }

    /** WebSocket 推送事件总数 */
    public int getWsEventCount() {
        return wsHandler.getEventCount();
    }

    /** 清空 WebSocket 推送事件历史 */
    public void clearWsEvents() {
        wsHandler.clearEvents();
    }

    private boolean isPilotMode() {
        return runtimeConfig.getDeviceMode() == DeviceMode.PILOT;
    }

    private HivemindHttpClient.HivemindResponse notPilotMode() {
        return new HivemindHttpClient.HivemindResponse(false, -1, "非 Pilot 模式，地图元素功能不可用", null);
    }
}
