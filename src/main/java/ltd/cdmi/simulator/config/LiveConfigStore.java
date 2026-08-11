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

package ltd.cdmi.simulator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 直播推流配置持久化存储。
 * <p>将 live 配置（realPushEnabled/ffmpegPath/videoDir）持久化到本地 JSON 文件，
 * 应用重启后自动恢复，避免用户通过 REST API 修改的配置丢失。</p>
 * <p>文件位置：{@code ${user.home}/.hivemind-simulator/live-config.json}</p>
 * <p>异常容忍：文件不存在或读取失败时降级为 yml 默认值（仅告警日志，不阻断启动）；
 * 写入失败时仅告警日志，不影响内存中的配置更新。</p>
 */
@Component
public class LiveConfigStore {

    private static final Logger log = LoggerFactory.getLogger(LiveConfigStore.class);

    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();

    public LiveConfigStore() {
        this.configPath = Path.of(System.getProperty("user.home"),
                ".hivemind-simulator", "live-config.json");
    }

    /**
     * 持久化的 live 配置快照。
     *
     * @param realPushEnabled 是否启用真实推流
     * @param ffmpegPath      ffmpeg 可执行文件路径
     * @param videoDir        视频文件目录
     */
    public record LiveConfig(boolean realPushEnabled, String ffmpegPath, String videoDir) {}

    /**
     * 从文件加载持久化的 live 配置。
     * @return 配置快照；文件不存在或读取失败返回 null（调用方使用 yml 默认值）
     */
    public LiveConfig load() {
        if (!Files.exists(configPath)) {
            log.debug("live 配置文件不存在，使用 yml 默认值: {}", configPath);
            return null;
        }
        try {
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            return mapper.readValue(json, LiveConfig.class);
        } catch (IOException e) {
            log.warn("加载 live 配置失败，使用 yml 默认值: {} - {}", configPath, e.getMessage());
            return null;
        }
    }

    /**
     * 将 live 配置持久化到文件。
     * <p>写入失败仅记录告警日志，不抛异常，不影响内存中的配置更新。</p>
     *
     * @param realPushEnabled 是否启用真实推流
     * @param ffmpegPath      ffmpeg 可执行文件路径
     * @param videoDir        视频文件目录
     */
    public void save(boolean realPushEnabled, String ffmpegPath, String videoDir) {
        try {
            Files.createDirectories(configPath.getParent());
            LiveConfig config = new LiveConfig(realPushEnabled,
                    ffmpegPath != null ? ffmpegPath : "",
                    videoDir != null ? videoDir : "");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configPath, json, StandardCharsets.UTF_8);
            log.debug("live 配置已持久化: {}", configPath);
        } catch (IOException e) {
            log.warn("保存 live 配置失败: {} - {}", configPath, e.getMessage());
        }
    }
}
