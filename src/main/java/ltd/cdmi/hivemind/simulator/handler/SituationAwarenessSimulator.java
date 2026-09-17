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

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.http.HivemindHttpClient;
import ltd.cdmi.hivemind.simulator.http.api.DeviceTopoApi;
import ltd.cdmi.hivemind.simulator.ws.handler.SituationAwarenessWsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 态势感知模拟器。
 * <p>协调设备拓扑 HTTP API 和态势感知 WebSocket 事件日志。
 * <p>Pilot 上线后：
 * <ul>
 *   <li>主动调用一次"获取设备拓扑列表"（DJI 文档要求：PILOT 首次上线后获取工作空间下所有设备列表及拓扑）</li>
 *   <li>WebSocket 连接由 {@link MapElementSimulator} 管理（共享 {@code HivemindWsClient}），
 *       本类不重复管理连接</li>
 * </ul>
 * <p>提供 REST API 供前端查询态势感知 WebSocket 推送事件历史。
 * <p>仅 Pilot 模式激活。</p>
 */
@Component
public class SituationAwarenessSimulator {

    private static final Logger log = LoggerFactory.getLogger(SituationAwarenessSimulator.class);

    private final DeviceTopoApi deviceTopoApi;
    private final RuntimeConfig runtimeConfig;
    private final SituationAwarenessWsHandler wsHandler;

    public SituationAwarenessSimulator(DeviceTopoApi deviceTopoApi, RuntimeConfig runtimeConfig,
                                       SituationAwarenessWsHandler wsHandler) {
        this.deviceTopoApi = deviceTopoApi;
        this.runtimeConfig = runtimeConfig;
        this.wsHandler = wsHandler;
    }

    /**
     * Pilot 上线后初始化：主动调用"获取设备拓扑列表"。
     * <p>由 PilotOnlineService 在 update_topo 成功后调用。
     * <p>DJI 文档：PILOT 在首次上线后，会发送 http 请求去获取同一个工作空间下的所有设备列表及其拓扑。
     * <p>注意：WebSocket 连接由 {@link MapElementSimulator#init()} 统一管理，本方法不重复连接。
     */
    public void init() {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT) {
            return;
        }
        log.info("态势感知模拟器初始化: 主动获取设备拓扑列表");
        fetchDeviceTopo();
    }

    /** 获取设备拓扑列表（首次上线或前端手动触发） */
    public HivemindHttpClient.HivemindResponse fetchDeviceTopo() {
        return deviceTopoApi.getDeviceTopo();
    }

    /**
     * 获取态势感知 WebSocket 推送事件历史（供 Web 控制台查询）。
     * <p>返回最新 500 条。
     */
    public List<Map<String, Object>> getWsEvents() {
        return wsHandler.getEvents();
    }

    /** 态势感知 WebSocket 推送事件总数 */
    public int getWsEventCount() {
        return wsHandler.getEventCount();
    }

    /** 清空态势感知 WebSocket 推送事件历史 */
    public void clearWsEvents() {
        wsHandler.clearEvents();
    }
}
