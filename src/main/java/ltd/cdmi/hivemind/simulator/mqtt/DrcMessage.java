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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DRC 远程控制消息格式工具。
 * <p>DRC 消息与 OSD 消息格式不同：
 * <ul>
 *   <li>OSD：{@code {bid, data, timestamp, version}}</li>
 *   <li>DRC 事件推送：{@code {method, data, timestamp, seq}}</li>
 *   <li>DRC 命令回复：{@code {method, data, seq}}</li>
 * </ul>
 * <p>DRC 消息分两类：
 * <ul>
 *   <li><b>事件推送</b>（设备→云）：设备主动推送，包含 timestamp 时间戳和 seq 递增序号。
 *       DJI 文档中 delay_info_push/hsi_info_push/osd_info_push 示例使用 timestamp，
 *       drc_camera_osd_info_push 说明 seq 与 data 同级。同时包含两者以覆盖所有场景。</li>
 *   <li><b>命令回复</b>（设备→云）：应答平台下发的命令，seq 与命令一致</li>
 * </ul>
 * <p>详见 DJI Cloud API <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/dji-rc-plus-2/drc.html">Pilot 远程控制</a>。
 */
public final class DrcMessage {

    private DrcMessage() {}

    /** DRC 事件推送 seq 计数器，线程安全递增 */
    private static final AtomicInteger eventSeq = new AtomicInteger(0);

    /**
     * 构造 DRC 事件推送消息（含 timestamp 和 seq）。
     * <p>DJI 文档中不同事件推送对 timestamp/seq 的要求不同：
     * <ul>
     *   <li>delay_info_push/hsi_info_push/osd_info_push：示例含 timestamp</li>
     *   <li>drc_camera_osd_info_push：说明 seq 与 data 同级</li>
     * </ul>
     * 同时包含两者以覆盖所有场景，多余字段不影响平台解析。</p>
     * @param method DRC 方法名，如 {@code "osd_info_push"}
     * @param data   消息数据
     * @return DRC 消息体 {@code {method, data, timestamp, seq}}
     */
    public static Map<String, Object> event(String method, Map<String, Object> data) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("method", method);
        msg.put("data", data != null ? data : Map.of());
        msg.put("timestamp", System.currentTimeMillis());
        msg.put("seq", eventSeq.incrementAndGet());
        return msg;
    }

    /**
     * 构造 DRC 命令回复消息（seq 与命令一致）。
     * @param method DRC 方法名，与命令相同
     * @param data   回复数据（含 result 字段）
     * @param seq    命令的 seq
     * @return DRC 消息体 {@code {method, data, seq}}
     */
    public static Map<String, Object> reply(String method, Map<String, Object> data, int seq) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("method", method);
        msg.put("data", data != null ? data : Map.of());
        msg.put("seq", seq);
        return msg;
    }
}
