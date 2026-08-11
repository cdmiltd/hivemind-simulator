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

package ltd.cdmi.simulator.diagnostic;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断日志采集器：在内存中缓存 S/P/M 诊断记录，供前端日志面板查看。
 * <p>生命周期与 MQTT 连接绑定：断开 MQTT 时调用 {@link #clear()} 清空，
 * 重新连接后从零采集。</p>
 * <p>线程安全：内部使用 synchronizedList。</p>
 */
@Component
public class DiagnosticLogRecorder {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final List<Map<String, Object>> logs = Collections.synchronizedList(new ArrayList<>());

    /**
     * 记录一条诊断日志。
     * @param code 诊断码（如 P-5、S-3）
     * @param method 涉及的 method（可为空）
     * @param detail 详细描述
     */
    public void record(DiagnosticCode code, String method, String detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()).format(TIME_FMT));
        entry.put("code", code.code());
        entry.put("category", code.category());
        entry.put("description", code.description());
        entry.put("method", method != null ? method : "-");
        entry.put("detail", detail);
        logs.add(entry);
    }

    /**
     * 获取所有诊断日志（按时间正序）。
     * @return 日志列表的快照副本
     */
    public List<Map<String, Object>> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    /**
     * 清空所有诊断日志。
     * <p>在 MQTT 断开时调用。</p>
     */
    public void clear() {
        logs.clear();
    }
}
