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

package ltd.cdmi.hivemind.simulator.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行时配置持久化存储（直播推流 + 媒体上传 + 机场位置 + Pilot 上云 HTTP/WS 配置 + 唯一 SN 模式的设备型号与 SN）。
 * <p>将 live 配置（realPushEnabled/ffmpegPath/videoDir）、媒体配置（mediaDir）、机场位置（lat/lng/height）、
 * Pilot 上云配置（hivemind HTTP base-url/token + WebSocket url/token）、
 * 唯一 SN 模式下的设备型号与 SN（dockType/droneType/dockSn/droneSn，仅 unique-enabled=true 时写入）
 * 持久化到本地 JSON 文件，应用重启后自动恢复，避免用户通过 REST API 修改的配置丢失。</p>
 * <p>文件位置（优先级从高到低）：
 * <ol>
 *   <li>环境变量 {@code SIMULATOR_CONFIG_DIR}/live-config.json（Docker / K8s 部署时使用，挂载 named volume）</li>
 *   <li>系统属性 {@code simulator.config.dir}/live-config.json（自定义部署）</li>
 *   <li>默认路径 {@code ${user.home}/.hivemind-simulator/live-config.json}（本地开发 / 桌面端）</li>
 * </ol></p>
 * <p>异常容忍：父目录不存在时自动创建；文件不存在或读取失败时降级为 yml 默认值（仅告警日志，不阻断启动）；
 * 写入失败时仅告警日志，不影响内存中的配置更新。</p>
 * <p>向后兼容：旧配置文件缺少 location 字段时，Jackson 自动填充 0.0，RuntimeConfig 检测到 0.0 时回退到 yml 默认值；
 * 缺少 pilot 字段时 Jackson 自动填充 null，RuntimeConfig 检测到 null 时保持 yml 默认值（不覆盖）；
 * 缺少 sn 字段（唯一 SN 模式引入前）时同样填充 null，RuntimeConfig 按首次启动生成新 SN（TC-REG-031）。</p>
 */
@Component
public class LiveConfigStore {

    private static final Logger log = LoggerFactory.getLogger(LiveConfigStore.class);

    /** 环境变量名：指定配置存储目录（Docker 部署时挂载 named volume 到此路径） */
    public static final String ENV_CONFIG_DIR = "SIMULATOR_CONFIG_DIR";
    /** 系统属性名：指定配置存储目录（优先级低于环境变量） */
    public static final String PROP_CONFIG_DIR = "simulator.config.dir";
    /** 配置文件名（无论使用哪个目录，文件名固定） */
    public static final String CONFIG_FILE = "live-config.json";

    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();

    public LiveConfigStore() {
        this.configPath = resolveConfigPath();
        log.info("配置持久化路径: {}", configPath.toAbsolutePath());
    }

    /**
     * 按优先级解析配置文件路径：
     * 环境变量 SIMULATOR_CONFIG_DIR → 系统属性 simulator.config.dir → ${user.home}/.hivemind-simulator
     */
    private Path resolveConfigPath() {
        String dir = System.getenv(ENV_CONFIG_DIR);
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir, CONFIG_FILE);
        }
        dir = System.getProperty(PROP_CONFIG_DIR);
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir, CONFIG_FILE);
        }
        return Path.of(System.getProperty("user.home"), ".hivemind-simulator", CONFIG_FILE);
    }

    /**
     * 持久化的配置快照（直播推流 + 媒体上传 + 机场位置 + Pilot 上云 HTTP/WS 配置 + 唯一 SN 模式的型号与 SN）。
     *
     * @param realPushEnabled 是否启用真实推流
     * @param ffmpegPath      ffmpeg 可执行文件路径
     * @param videoDir        视频文件目录
     * @param mediaDir        媒体文件目录（模拟照片/视频）
     * @param locationLatitude  机场纬度
     * @param locationLongitude 机场经度
     * @param locationHeight    机场海拔（米）
     * @param pilotHttpBaseUrl Pilot 上云 hivemind HTTP API 基础地址
     * @param pilotHttpToken Pilot 上云 hivemind HTTP 鉴权 token（x-auth-token）
     * @param pilotWsUrl     Pilot 上云 hivemind WebSocket 地址
     * @param pilotWsToken   Pilot 上云 hivemind WebSocket 鉴权 token
     * @param dockType       唯一 SN 模式：机场型号枚举名（默认模式为 null，不序列化）
     * @param droneType      唯一 SN 模式：飞行器型号枚举名（默认模式为 null，不序列化）
     * @param dockSn         唯一 SN 模式：机场 SN（默认模式为 null，不序列化）
     * @param droneSn        唯一 SN 模式：飞行器 SN（默认模式为 null，不序列化）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LiveConfig(boolean realPushEnabled, String ffmpegPath, String videoDir, String mediaDir,
                              double locationLatitude, double locationLongitude, double locationHeight,
                              String pilotHttpBaseUrl, String pilotHttpToken, String pilotWsUrl, String pilotWsToken,
                              String dockType, String droneType, String dockSn, String droneSn,
                              String dockSnOverride, String droneSnOverride) {

        /**
         * 旧格式兼容构造器（唯一 SN 模式引入前的 11 字段格式，sn 字段为 null）。
         * <p>既有调用方与旧格式配置文件的语义表示；null 字段不写入 JSON（{@code @JsonInclude(NON_NULL)}）。</p>
         */
        public LiveConfig(boolean realPushEnabled, String ffmpegPath, String videoDir, String mediaDir,
                          double locationLatitude, double locationLongitude, double locationHeight,
                          String pilotHttpBaseUrl, String pilotHttpToken, String pilotWsUrl, String pilotWsToken) {
            this(realPushEnabled, ffmpegPath, videoDir, mediaDir,
                    locationLatitude, locationLongitude, locationHeight,
                    pilotHttpBaseUrl, pilotHttpToken, pilotWsUrl, pilotWsToken,
                    null, null, null, null, null, null);
        }

        /**
         * SN 覆盖引入前的 15 字段兼容构造器（override 字段为 null）。
         */
        public LiveConfig(boolean realPushEnabled, String ffmpegPath, String videoDir, String mediaDir,
                          double locationLatitude, double locationLongitude, double locationHeight,
                          String pilotHttpBaseUrl, String pilotHttpToken, String pilotWsUrl, String pilotWsToken,
                          String dockType, String droneType, String dockSn, String droneSn) {
            this(realPushEnabled, ffmpegPath, videoDir, mediaDir,
                    locationLatitude, locationLongitude, locationHeight,
                    pilotHttpBaseUrl, pilotHttpToken, pilotWsUrl, pilotWsToken,
                    dockType, droneType, dockSn, droneSn, null, null);
        }
    }

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
     * 将配置持久化到文件。
     * <p>写入失败仅记录告警日志，不抛异常，不影响内存中的配置更新。</p>
     *
     * @param realPushEnabled 是否启用真实推流
     * @param ffmpegPath      ffmpeg 可执行文件路径
     * @param videoDir        视频文件目录
     * @param mediaDir        媒体文件目录
     * @param locationLatitude  机场纬度
     * @param locationLongitude 机场经度
     * @param locationHeight    机场海拔（米）
     * @param pilotHttpBaseUrl Pilot 上云 hivemind HTTP API 基础地址
     * @param pilotHttpToken Pilot 上云 hivemind HTTP 鉴权 token
     * @param pilotWsUrl     Pilot 上云 hivemind WebSocket 地址
     * @param pilotWsToken   Pilot 上云 hivemind WebSocket 鉴权 token
     * @param dockType       唯一 SN 模式：机场型号枚举名（默认模式传 null，不写入文件）
     * @param droneType      唯一 SN 模式：飞行器型号枚举名（默认模式传 null）
     * @param dockSn         唯一 SN 模式：机场 SN（默认模式传 null）
     * @param droneSn        唯一 SN 模式：飞行器 SN（默认模式传 null）
     * @param dockSnOverride SN 手动覆盖：机场 SN（未覆盖传 null，不写入文件）
     * @param droneSnOverride SN 手动覆盖：飞行器 SN（未覆盖传 null）
     */
    public void save(boolean realPushEnabled, String ffmpegPath, String videoDir, String mediaDir,
                     double locationLatitude, double locationLongitude, double locationHeight,
                     String pilotHttpBaseUrl, String pilotHttpToken, String pilotWsUrl, String pilotWsToken,
                     String dockType, String droneType, String dockSn, String droneSn,
                     String dockSnOverride, String droneSnOverride) {
        try {
            Files.createDirectories(configPath.getParent());
            LiveConfig config = new LiveConfig(realPushEnabled,
                    ffmpegPath != null ? ffmpegPath : "",
                    videoDir != null ? videoDir : "",
                    mediaDir != null ? mediaDir : "",
                    locationLatitude, locationLongitude, locationHeight,
                    pilotHttpBaseUrl != null ? pilotHttpBaseUrl : "",
                    pilotHttpToken != null ? pilotHttpToken : "",
                    pilotWsUrl != null ? pilotWsUrl : "",
                    pilotWsToken != null ? pilotWsToken : "",
                    dockType, droneType, dockSn, droneSn,
                    dockSnOverride, droneSnOverride);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configPath, json, StandardCharsets.UTF_8);
            log.debug("配置已持久化: {}", configPath);
        } catch (IOException e) {
            log.warn("保存配置失败: {} - {}", configPath, e.getMessage());
        }
    }
}
