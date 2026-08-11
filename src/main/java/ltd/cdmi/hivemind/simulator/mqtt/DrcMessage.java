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
 *   <li>DRC：{@code {method, data, seq}}</li>
 * </ul>
 * <p>DRC 消息分两类：
 * <ul>
 *   <li><b>事件推送</b>（设备→云）：设备主动推送，seq 自动递增</li>
 *   <li><b>命令回复</b>（设备→云）：应答平台下发的命令，seq 与命令一致</li>
 * </ul>
 * <p>详见 DJI Cloud API <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html">Dock3 远程控制</a>。
 */
public final class DrcMessage {

    private DrcMessage() {}

    /** DRC seq 计数器，线程安全递增（事件推送使用） */
    private static final AtomicInteger eventSeq = new AtomicInteger(0);

    /**
     * 构造 DRC 事件推送消息（seq 自动递增）。
     * @param method DRC 方法名，如 {@code "drc_drone_state_push"}
     * @param data   消息数据
     * @return DRC 消息体 {@code {method, data, seq}}
     */
    public static Map<String, Object> event(String method, Map<String, Object> data) {
        return build(method, data, eventSeq.incrementAndGet());
    }

    /**
     * 构造 DRC 命令回复消息（seq 与命令一致）。
     * @param method DRC 方法名，与命令相同
     * @param data   回复数据（含 result 字段）
     * @param seq    命令的 seq
     * @return DRC 消息体 {@code {method, data, seq}}
     */
    public static Map<String, Object> reply(String method, Map<String, Object> data, int seq) {
        return build(method, data, seq);
    }

    /**
     * 构造 DRC 消息体。
     */
    private static Map<String, Object> build(String method, Map<String, Object> data, int seq) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("method", method);
        msg.put("data", data != null ? data : Map.of());
        msg.put("seq", seq);
        return msg;
    }
}
