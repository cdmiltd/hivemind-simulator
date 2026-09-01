// Copyright (C) 2026 CDMI.LTD
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

package ltd.cdmi.hivemind.simulator.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT 消息日志持久化存储。
 * <p>每条消息以 JSON Lines 格式写入本地文件（每行一条 JSON），按日期滚动。
 * <p>文件位置：{@code ${user.home}/.hivemind-simulator/logs/messages-yyyy-MM-dd.jsonl}
 * <p>线程安全：写入使用 synchronized 保护。读取为只读操作，无锁。
 * <p>异常容忍：写入失败仅告警日志，不影响消息分发流程。
 */
@Component
public class MessageLogStore {

    private static final Logger log = LoggerFactory.getLogger(MessageLogStore.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Path logDir;
    private final ObjectMapper objectMapper;
    private volatile String currentDate;
    private volatile Path currentFile;

    public MessageLogStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.logDir = Path.of(System.getProperty("user.home"), ".hivemind-simulator", "logs");
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            log.warn("创建消息日志目录失败: {}", e.getMessage());
        }
    }

    /**
     * 持久化一条消息日志到本地文件。
     *
     * @param direction 方向（"send" / "recv"）
     * @param topic     MQTT topic
     * @param method    method 名称（可为空）
     * @param payload   消息内容
     * @param timestamp 时间戳（毫秒）
     */
    public synchronized void append(String direction, String topic, String method, String payload, long timestamp) {
        try {
            ensureFile();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", timestamp);
            entry.put("time", LocalDateTime.now().format(TIME_FMT));
            entry.put("direction", direction);
            entry.put("topic", topic);
            entry.put("method", method != null ? method : "");
            entry.put("payload", payload);
            String line = objectMapper.writeValueAsString(entry) + "\n";
            Files.write(currentFile, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 首次失败时输出完整堆栈定位根因；后续重复失败仅简略日志避免刷屏
            if (firstWriteError) {
                log.warn("写入消息日志文件失败（首次，输出完整堆栈）: file={}, error={}",
                        currentFile, e.getMessage(), e);
                firstWriteError = false;
            } else {
                log.warn("写入消息日志文件失败: {}", e.getMessage());
            }
        }
    }

    private volatile boolean firstWriteError = true;

    /**
     * 从本地文件读取历史消息日志（分页查询）。
     * <p>返回 timestamp < beforeTime 的最近 limit 条消息（正序：旧→新）。
     *
     * @param beforeTime 时间戳分界点（毫秒），返回此时间之前的消息。null 表示从最新开始
     * @param limit      返回条数
     * @return 消息日志列表（正序），每条含 {ts, time, direction, topic, method, payload}
     */
    public List<Map<String, Object>> queryHistory(Long beforeTime, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Path> files = getLogFilesReverse();
            for (Path file : files) {
                if (result.size() >= limit) break;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                // 倒序遍历（新→旧）
                for (int i = lines.size() - 1; i >= 0 && result.size() < limit; i--) {
                    String line = lines.get(i);
                    if (line.isBlank()) continue;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = objectMapper.readValue(line, Map.class);
                        long ts = ((Number) entry.get("ts")).longValue();
                        if (beforeTime != null && ts >= beforeTime) continue;
                        result.add(entry);
                    } catch (Exception e) {
                        // 跳过解析失败的行
                    }
                }
            }
            // 反转为正序（旧→新）
            Collections.reverse(result);
        } catch (Exception e) {
            log.warn("读取消息日志文件失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 获取日志文件列表（按日期倒序，最新的在前）。
     */
    public List<Path> getLogFiles() {
        return getLogFilesReverse();
    }

    /**
     * 获取指定日期的日志文件路径。
     */
    public Path getLogFile(String date) {
        return logDir.resolve("messages-" + date + ".jsonl");
    }

    /**
     * 获取日志目录路径。
     */
    public Path getLogDir() {
        return logDir;
    }

    /**
     * 确保当前日期对应的文件路径已就绪。日期变更时自动切换文件。
     * <p>注意：不调用 {@link Files#createFile} 预创建文件——
     * {@link Files#write} 带 {@link StandardOpenOption#CREATE} 和 {@link StandardOpenOption#APPEND}
     * 会在文件不存在时自动创建。预创建文件在某些 Windows 环境（杀软实时扫描/目录权限继承延迟）
     * 会抛 AccessDeniedException，属于不必要的操作。</p>
     */
    private void ensureFile() throws IOException {
        String today = LocalDate.now().format(DATE_FMT);
        if (!today.equals(currentDate)) {
            currentDate = today;
            currentFile = logDir.resolve("messages-" + today + ".jsonl");
        }
    }

    /**
     * 获取日志文件列表（按文件名倒序，即最新日期在前）。
     */
    private List<Path> getLogFilesReverse() {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(logDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("messages-") && p.toString().endsWith(".jsonl"))
                    .sorted(Collections.reverseOrder())
                    .forEach(files::add);
        } catch (Exception e) {
            log.warn("列出日志文件失败: {}", e.getMessage());
        }
        return files;
    }
}
