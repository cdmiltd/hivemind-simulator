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

package ltd.cdmi.hivemind.simulator.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.RequestsMethod;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.StatusMethod;
import ltd.cdmi.dji.cloudapi.sdk.telemetry.StateField;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.TopicSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 机场上云流程模拟：config 请求 → airport_bind_status → airport_organization_get → airport_organization_bind → update_topo 上线。
 * <p>时序遵循 DJI Cloud API 机场上云交互规范（update_topo 属于注册成功后的上线步骤，不在注册流程内）。
 * 通过 tid 匹配云端回复，实现异步等待。</p>
 */
@Service
public class DockOnlineService {

    private static final Logger log = LoggerFactory.getLogger(DockOnlineService.class);
    /** 等待云端回复超时时间（秒） */
    private static final long REPLY_TIMEOUT_SECONDS = 10;
    /** config 请求超时重试次数（参考真实设备行为：超时重试3次，全失败才停止注册） */
    private static final int CONFIG_RETRY_MAX = 3;
    /** config 请求重试间隔（秒） */
    private static final long CONFIG_RETRY_INTERVAL_SECONDS = 3;

    // PSDK 喊话器设备标识已迁移至 PsdkConstants（单一真相源，state + DRC 通道共用）

    /** 上线流程结果：success + code（"0"=成功，P/S/M 码=诊断错误，DJI 码如 210229=协议层错误）+ step（失败步骤，成功时为 null） */
    public record OnlineResult(boolean success, String code, String step) {
        /** 注册流程步骤名称（用于前端进度提示和失败时定位失败环节，TC-REG-019） */
        public static final String STEP_MQTT = "MQTT 连接";
        public static final String STEP_CONFIG = "config 配置请求";
        public static final String STEP_BIND_STATUS = "绑定状态查询";
        public static final String STEP_ORG_GET = "组织信息查询";
        public static final String STEP_ORG_BIND = "设备绑定";
        public static final String STEP_UPDATE_TOPO = "设备上线";

        public static OnlineResult ok() { return new OnlineResult(true, "0", null); }
        /** 兼容旧调用（step=null），主要用于 checkXxxResult 方法 */
        public static OnlineResult fail(DiagnosticCode code) { return new OnlineResult(false, code.code(), null); }
        public static OnlineResult fail(int djiResultCode) { return new OnlineResult(false, String.valueOf(djiResultCode), null); }
        public static OnlineResult fail(DiagnosticCode code, String step) { return new OnlineResult(false, code.code(), step); }
        public static OnlineResult fail(int djiResultCode, String step) { return new OnlineResult(false, String.valueOf(djiResultCode), step); }
        /** 为 checkXxxResult 返回的结果补充步骤信息 */
        public OnlineResult withStep(String step) { return new OnlineResult(success, code, step); }
    }

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final ObjectMapper objectMapper;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final TopicSchema dockTopicSchema;

    /** tid → CompletableFuture，用于等待 status_reply / requests_reply */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingReplies = new ConcurrentHashMap<>();

    /** 已注册 reply 监听器的 dockSn（dockSn 变化时需重新注册） */
    private volatile String registeredReplyDockSn;

    public DockOnlineService(SimulatorProperties props, MqttClientManager mqtt, DeviceState state,
                             ObjectMapper objectMapper, RuntimeConfig runtimeConfig,
                             DiagnosticLogRecorder diagnosticRecorder,
                             DockTopicSchema dockTopicSchema) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
        this.dockTopicSchema = dockTopicSchema;
        ensureReplyListeners();
    }

    /**
     * 确保 status_reply 和 requests_reply 监听器已注册（dockSn 变化时重新注册）。
     * <p>切换 Dock 类型时 {@link RuntimeConfig#setDockType} 会自动更新 dockSn，
     * 此方法确保监听器始终绑定当前 dockSn 对应的 topic。</p>
     */
    private void ensureReplyListeners() {
        String dockSn = runtimeConfig.getDockSn();
        if (dockSn.equals(registeredReplyDockSn)) {
            return;
        }
        mqtt.addListener(dockTopicSchema.topic(dockTopicSchema.statusReply(), dockSn), this::handleReply);
        mqtt.addListener(dockTopicSchema.topic(dockTopicSchema.requestsReply(), dockSn), this::handleReply);
        registeredReplyDockSn = dockSn;
        log.info("DockOnlineService 已注册 reply 监听器: dockSn={}", dockSn);
    }

    /**
     * 处理云端回复：按 tid 匹配并完成 pending future。
     */
    private void handleReply(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String tid = node.path("tid").asText();
            CompletableFuture<JsonNode> future = pendingReplies.remove(tid);
            if (future != null) {
                future.complete(node);
                log.debug("回复匹配成功 tid={}, method={}", tid, node.path("method").asText());
            }
        } catch (Exception e) {
            log.error("解析回复失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 上线流程 ====================

    /**
     * 执行完整上线流程。
     * <p>时序（与 DJI Cloud API 机场上云交互规范一致）：
     * <ol>
     *   <li>config 请求（获取 License 校验所需参数，超时重试3次间隔3秒）</li>
     *   <li>airport_bind_status 查询设备绑定信息（result≠0 表示错误，停止注册并透传 result）</li>
     *   <li>airport_organization_get 查询组织信息（result≠0 表示错误，停止注册并透传 result）</li>
     *   <li>airport_organization_bind 绑定到组织（result≠0 停止注册；result=0 但 err_infos 非空表示设备级失败，透传第一个 err_code）</li>
     *   <li>update_topo 上线（注册成功后通知平台设备拓扑，对齐 DJI 行为：超时不停止流程）</li>
     * </ol>
     * @return OnlineResult，success=true 表示上线成功
     */
    public OnlineResult online() {
        if (state.isOnline()) {
            log.warn("设备已在线，忽略重复上线");
            return OnlineResult.ok();
        }
        if (!mqtt.isConnected()) {
            log.warn("MQTT 未连接，无法执行上线流程（请检查 MQTT 主机/端口/用户名/密码是否正确）");
            return OnlineResult.fail(DiagnosticCode.SIMULATOR_MQTT_NOT_CONNECTED, OnlineResult.STEP_MQTT);
        }
        try {
            // ==================== 注册流程 ====================

            // 1. 发 config 请求获取 License 校验参数（超时重试3次，间隔3秒，全失败才停止注册）
            JsonNode configReply = null;
            for (int attempt = 1; attempt <= CONFIG_RETRY_MAX; attempt++) {
                configReply = sendRequest(RequestsMethod.CONFIG.methodName(), Map.of(
                        "config_type", "json",
                        "config_scope", "product"
                ));
                if (configReply != null) {
                    break;
                }
                if (attempt < CONFIG_RETRY_MAX) {
                    log.warn("config 请求超时，平台无响应，{}秒后第{}次重试", CONFIG_RETRY_INTERVAL_SECONDS, attempt + 1);
                    try {
                        TimeUnit.SECONDS.sleep(CONFIG_RETRY_INTERVAL_SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("config 重试等待被中断，停止注册流程");
                        return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY, OnlineResult.STEP_CONFIG);
                    }
                }
            }
            if (configReply == null) {
                log.warn("config 请求{}次均超时，平台无响应，停止注册流程", CONFIG_RETRY_MAX);
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY, OnlineResult.STEP_CONFIG);
            }
            log.info("config 请求成功: app_id={}", configReply.path("data").path("app_id").asText());

            // 校验云端返回的 app_license 与本地配置（用户填写）一致，不一致停止注册
            // 若用户未填写 license（留空），则跳过校验，不模拟 License 认证
            String localLicense = runtimeConfig.getAppLicense();
            if (localLicense == null || localLicense.isBlank()) {
                log.info("本地未配置 app_license，跳过 License 校验");
            } else {
                String remoteLicense = configReply.path("data").path("app_license").asText();
                if (!localLicense.equals(remoteLicense)) {
                    log.warn("app_license 校验失败：本地与云端不一致（local={}, remote={}）", localLicense, remoteLicense);
                    return OnlineResult.fail(DiagnosticCode.PLATFORM_LICENSE_MISMATCH, OnlineResult.STEP_CONFIG);
                }
                log.info("app_license 校验通过");
            }

            // 2. 发 airport_bind_status 查询绑定状态
            //    result≠0 表示请求级错误，停止注册；result=0 时不根据 bind_status 内容跳过后续步骤（TC-REG-003）
            JsonNode bindStatusReply = sendRequest(RequestsMethod.AIRPORT_BIND_STATUS.methodName(), Map.of(
                    "devices", List.of(
                            Map.of("sn", runtimeConfig.getDockSn()),
                            Map.of("sn", runtimeConfig.getDroneSn())
                    )
            ));
            if (bindStatusReply == null) {
                log.warn("airport_bind_status 超时，平台无响应，停止注册流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY, OnlineResult.STEP_BIND_STATUS);
            }
            log.info("airport_bind_status 回复: result={}", bindStatusReply.path("data").path("result").asText());
            OnlineResult bindStatusResult = checkBindStatusResult(bindStatusReply);
            if (!bindStatusResult.success()) {
                return bindStatusResult.withStep(OnlineResult.STEP_BIND_STATUS);
            }

            // 3. 发 airport_organization_get 查询组织信息（绑定码运行时可配）
            //    hivemind 据此校验绑定码：result≠0 表示错误（210229 绑定码错误等）
            String bindCode = runtimeConfig.getDeviceBindingCode();
            JsonNode orgGetReply = sendRequest(RequestsMethod.AIRPORT_ORGANIZATION_GET.methodName(), Map.of(
                    "device_binding_code", bindCode,
                    "organization_id", runtimeConfig.getOrganizationId()
            ));
            if (orgGetReply == null) {
                log.warn("airport_organization_get 超时，平台无响应，停止注册流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY, OnlineResult.STEP_ORG_GET);
            }
            log.info("airport_organization_get 回复: result={}", orgGetReply.path("data").path("result").asText());
            OnlineResult orgGetResult = checkOrgGetResult(orgGetReply);
            if (!orgGetResult.success()) {
                log.warn("组织信息查询失败: device_binding_code={}", bindCode);
                return orgGetResult.withStep(OnlineResult.STEP_ORG_GET);
            }

            // 4. 发 airport_organization_bind 绑定到组织（组织ID/绑定码运行时可配）
            //    绑定结果判断：result≠0 表示整体请求失败；result=0 时检查 err_infos 中是否有非0 err_code（设备级失败）
            String orgId = runtimeConfig.getOrganizationId();
            JsonNode orgBindReply = sendRequest(RequestsMethod.AIRPORT_ORGANIZATION_BIND.methodName(), Map.of(
                    "bind_devices", List.of(
                            Map.of(
                                    "sn", runtimeConfig.getDockSn(),
                                    "device_model_key", runtimeConfig.getDockType().modelKey(),
                                    "device_callsign", runtimeConfig.getDockSn(),
                                    "organization_id", orgId,
                                    "device_binding_code", bindCode
                            ),
                            Map.of(
                                    "sn", runtimeConfig.getDroneSn(),
                                    "device_model_key", runtimeConfig.getDroneType().modelKey(),
                                    "device_callsign", runtimeConfig.getDroneSn(),
                                    "organization_id", orgId,
                                    "device_binding_code", bindCode
                            )
                    )
            ));
            if (orgBindReply == null) {
                log.warn("airport_organization_bind 超时，平台无响应，停止注册流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY, OnlineResult.STEP_ORG_BIND);
            }
            log.info("airport_organization_bind 回复: result={}", orgBindReply.path("data").path("result").asText());
            OnlineResult orgBindResult = checkOrgBindResult(orgBindReply);
            if (!orgBindResult.success()) {
                return orgBindResult.withStep(OnlineResult.STEP_ORG_BIND);
            }

            log.info("机场注册成功: dockSn={}, droneSn={}", runtimeConfig.getDockSn(), runtimeConfig.getDroneSn());

            // ==================== 上线流程（注册成功后执行）====================

            // 5. 发 update_topo 上线（注册成功后通知平台设备拓扑）
            if (!sendUpdateTopo()) {
                // result≠0，平台拓扑更新失败，停止上线
                log.warn("update_topo 失败，停止上线流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY, OnlineResult.STEP_UPDATE_TOPO);
            }

            state.setOnline(true);
            // 推送机场 state 属性初始值（pushMode=1 属性，含 live_capacity）
            publishDockState();
            log.info("机场上线流程完成: dockSn={}, droneSn={}", runtimeConfig.getDockSn(), runtimeConfig.getDroneSn());
            return OnlineResult.ok();
        } catch (Exception e) {
            log.error("上线流程异常: {}", e.getMessage(), e);
            return OnlineResult.fail(DiagnosticCode.SIMULATOR_PARSE_BUG);
        }
    }

    /**
     * 解析 airport_bind_status 回复，判断绑定状态查询是否成功。
     * <p>判断逻辑：result≠0 表示请求级错误，透传 result 码。
     * <p>注意：result=0 时不根据 bind_status 内容（如 is_device_bind_organization）跳过后续步骤，
     * 每一步无条件执行（TC-REG-003）。
     *
     * @param reply airport_bind_status 回复 JSON
     * @return OnlineResult，success=true 表示查询成功
     */
    public OnlineResult checkBindStatusResult(JsonNode reply) {
        int result = reply.path("data").path("result").asInt(-1);
        if (result != 0) {
            log.warn("绑定状态查询失败: result={}", result);
            return OnlineResult.fail(result);
        }
        return OnlineResult.ok();
    }

    /**
     * 解析 airport_organization_get 回复，判断组织信息查询是否成功。
     * <p>判断逻辑：result≠0 表示错误（210229 绑定码错误、210234 组织不存在等），透传 result 码。
     *
     * @param reply airport_organization_get 回复 JSON
     * @return OnlineResult，success=true 表示查询成功
     */
    public OnlineResult checkOrgGetResult(JsonNode reply) {
        int result = reply.path("data").path("result").asInt(-1);
        if (result != 0) {
            log.warn("组织信息查询失败: result={}", result);
            return OnlineResult.fail(result);
        }
        return OnlineResult.ok();
    }

    /**
     * 解析 airport_organization_bind 回复，判断设备绑定是否成功。
     * <p>判断逻辑（对齐 DJI Cloud API 文档）：
     * <ol>
     *   <li>result≠0：整体请求失败，透传 result 码</li>
     *   <li>result=0 且 err_infos 为空/不存在：绑定成功</li>
     *   <li>result=0 且 err_infos 非空：遍历检查 err_code，存在非0 err_code 则设备级绑定失败，全部为0则成功</li>
     * </ol>
     *
     * @param reply airport_organization_bind 回复 JSON
     * @return OnlineResult，success=true 表示绑定成功
     */
    public OnlineResult checkOrgBindResult(JsonNode reply) {
        int result = reply.path("data").path("result").asInt(-1);
        if (result != 0) {
            log.warn("设备绑定失败: result={}", result);
            return OnlineResult.fail(result);
        }
        JsonNode errInfos = reply.path("data").path("output").path("err_infos");
        if (errInfos.isArray() && errInfos.size() > 0) {
            for (JsonNode errInfo : errInfos) {
                int errCode = errInfo.path("err_code").asInt(-1);
                if (errCode != 0) {
                    log.warn("设备绑定失败: err_infos={}", errInfos);
                    return OnlineResult.fail(errCode);
                }
            }
        }
        return OnlineResult.ok();
    }

    /**
     * 仅执行上线流程（跳过注册），用于已注册设备的开机自动重连。
     * <p>设备关机前已注册成功（localStorage.registered=true），开机重连时只需：
     * <ol>
     *   <li>update_topo 上线（通知平台设备拓扑）</li>
     *   <li>标记在线 + 上报 live_capacity</li>
     * </ol>
     * 跳过 config/airport_bind_status/airport_organization_get/airport_organization_bind 注册步骤。
     * @return OnlineResult，success=true 表示上线成功
     */
    public OnlineResult onlineOnly() {
        if (state.isOnline()) {
            log.warn("设备已在线，忽略重复上线");
            return OnlineResult.ok();
        }
        if (!mqtt.isConnected()) {
            log.warn("MQTT 未连接，无法执行上线流程（请检查 MQTT 主机/端口/用户名/密码是否正确）");
            return OnlineResult.fail(DiagnosticCode.SIMULATOR_MQTT_NOT_CONNECTED, OnlineResult.STEP_MQTT);
        }
        try {
            log.info("跳过注册流程，直接上线（设备已注册）: dockSn={}", runtimeConfig.getDockSn());
            if (!sendUpdateTopo()) {
                log.warn("update_topo 失败，停止上线流程");
                return OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY, OnlineResult.STEP_UPDATE_TOPO);
            }
            state.setOnline(true);
            // 推送机场 state 属性初始值（pushMode=1 属性，含 live_capacity）
            publishDockState();
            log.info("机场上线流程完成: dockSn={}, droneSn={}", runtimeConfig.getDockSn(), runtimeConfig.getDroneSn());
            return OnlineResult.ok();
        } catch (Exception e) {
            log.error("上线流程异常: {}", e.getMessage(), e);
            return OnlineResult.fail(DiagnosticCode.SIMULATOR_PARSE_BUG);
        }
    }

    /**
     * 下线：标记离线 + 发 update_topo 空 sub_devices 通知平台。
     * <p>MQTT 连接的断开由调用方（SimulatorController）处理。</p>
     */
    public void offline() {
        if (!state.isOnline()) {
            log.warn("设备已离线，忽略重复下线");
            return;
        }
        state.setOnline(false);  // 先标记离线，停止 OSD 上报

        // 发 update_topo 下线（sub_devices 为空表示下线）
        DockModel dockType = runtimeConfig.getDockType();
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", String.valueOf(dockType.domain()));
        data.put("type", dockType.type());
        data.put("sub_type", dockType.subType());
        data.put("device_secret", "secret");
        data.put("nonce", "nonce");
        data.put("sub_devices", List.of());
        data.put("thing_version", runtimeConfig.getThingVersion());
        publishStatus(StatusMethod.UPDATE_TOPO.methodName(), tid, bid, data);

        log.info("机场已下线: dockSn={}", runtimeConfig.getDockSn());
    }

    /**
     * 飞行器休眠时发送 update_topo 通知平台飞行器下线。
     * <p>机场仍在线，仅飞行器从激活切换为休眠。发送空 sub_devices 的 update_topo。</p>
     * <p>TODO 待核实：真实机场在飞行器休眠时发送的 update_topo 中 sub_devices 字段格式。
     * 当前默认为空列表（与设备下线格式一致），需根据实际场景下的真实指令为准。</p>
     * <p>核实依据：DJI SDK 源码中 sub_devices 为空 → INBOUND_STATUS_OFFLINE；
     * https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/dock-feature-set/dock-device-management.html</p>
     */
    public void publishDroneSleepTopo() {
        if (!state.isOnline()) {
            log.warn("设备未上线，跳过飞行器休眠 update_topo");
            return;
        }
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", String.valueOf(runtimeConfig.getDockType().domain()));
        data.put("type", runtimeConfig.getDockType().type());
        data.put("sub_type", runtimeConfig.getDockType().subType());
        data.put("device_secret", "secret");
        data.put("nonce", "nonce");
        data.put("sub_devices", List.of());
        data.put("thing_version", runtimeConfig.getThingVersion());
        publishStatus(StatusMethod.UPDATE_TOPO.methodName(), tid, bid, data);
        log.info("飞行器休眠，已发送 update_topo（sub_devices 为空）");
    }

    // ==================== 报文构造 ====================

    /**
     * 发送 update_topo 上线报文。
     * <p>发送后等待 status_reply：
     * <ul>
     *   <li>result=0 → 返回 true（上线成功）</li>
     *   <li>result≠0 → 返回 false（平台拓扑更新失败，停止上线）</li>
     *   <li>超时 → 返回 true（不停止上线，对齐 DJI 时序图行为）</li>
     * </ul>
     * TODO 待核实：真实机场收到 result≠0 后是否继续执行上线后续流程</p>
     *
     * @return true 表示继续上线，false 表示停止上线
     */
    /** 构造 update_topo 上线报文数据（sub_devices 根据飞行器激活状态决定） */
    private Map<String, Object> buildUpdateTopoData() {
        DockModel dockType = runtimeConfig.getDockType();
        DroneModel droneType = runtimeConfig.getDroneType();

        // DJI update_topo: data 顶层包含网关设备的 domain（string）、type（int）、sub_type（int）、
        // device_secret（text）、nonce（text）、thing_version（text）
        // sub_devices 根据飞行器激活状态决定：激活时包含飞行器，休眠时为空
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", String.valueOf(dockType.domain()));
        data.put("type", dockType.type());
        data.put("sub_type", dockType.subType());
        data.put("device_secret", "secret");
        data.put("nonce", "nonce");
        if (state.isDroneActivated()) {
            data.put("sub_devices", List.of(
                    Map.of(
                            "sn", runtimeConfig.getDroneSn(),
                            "domain", String.valueOf(droneType.domain()),
                            "type", droneType.type(),
                            "sub_type", droneType.subType(),
                            "index", "A",
                            "device_secret", "secret",
                            "nonce", "nonce",
                            "thing_version", runtimeConfig.getThingVersion()
                    )
            ));
        } else {
            data.put("sub_devices", List.of());
        }
        data.put("thing_version", runtimeConfig.getThingVersion());
        return data;
    }

    /**
     * 重发 update_topo 上线报文（不等待回复）。
     * <p>供监控器连接时获取设备拓扑：监控器后连接时可能错过之前的 update_topo，
     * 重发一次让监控器能发现已在线的设备。</p>
     * <p>设备未在线时不执行。</p>
     */
    public void resendTopo() {
        if (!state.isOnline()) return;
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();
        publishStatus(StatusMethod.UPDATE_TOPO.methodName(), tid, bid, buildUpdateTopoData());
        log.info("监控器连接，重发 update_topo 供监控器发现设备");
    }

    private boolean sendUpdateTopo() {
        ensureReplyListeners();
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();

        Map<String, Object> data = buildUpdateTopoData();

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingReplies.put(tid, future);
        publishStatus(StatusMethod.UPDATE_TOPO.methodName(), tid, bid, data);

        try {
            JsonNode reply = future.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int result = reply.path("data").path("result").asInt(-1);
            if (result == 0) {
                log.info("update_topo 已收到 status_reply: result=0");
                return true;
            } else {
                log.warn("update_topo status_reply 返回非零: result={}，停止上线", result);
                return false;
            }
        } catch (Exception e) {
            pendingReplies.remove(tid);
            log.warn("等待 status_reply 超时（不影响上线，对齐 DJI 行为）: {}", e.getMessage());
            diagnosticRecorder.record(DiagnosticCode.PLATFORM_NO_REPLY, StatusMethod.UPDATE_TOPO.methodName(),
                    "平台未回复 status_reply（超时 " + REPLY_TIMEOUT_SECONDS + "s），可能平台服务未启动或未实现 status_reply 回复");
            return true;
        }
    }

    /**
     * 发送 requests 请求（config/airport_bind_status/airport_organization_bind）并等待 requests_reply。
     * @param method 方法名
     * @param data 请求数据
     * @return 回复 JSON，超时返回 null
     */
    public JsonNode sendRequest(String method, Map<String, Object> data) {
        ensureReplyListeners();
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingReplies.put(tid, future);
        publishRequest(method, tid, bid, data);

        try {
            return future.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingReplies.remove(tid);
            log.warn("等待 requests_reply 超时: method={}, {}", method, e.getMessage());
            diagnosticRecorder.record(DiagnosticCode.PLATFORM_NO_REPLY, method,
                    "平台未回复 requests_reply（超时 " + REPLY_TIMEOUT_SECONDS + "s）");
            return null;
        }
    }

    /**
     * 发布 status 通道消息（sys/product/{sn}/status）。
     */
    private void publishStatus(String method, String tid, String bid, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", bid);
        envelope.put("data", data);
        envelope.put("tid", tid);
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("method", method);
        String topic = dockTopicSchema.topic(dockTopicSchema.status(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已发送 status: method={}, tid={}", method, tid);
    }

    /**
     * 发布 requests 通道消息（thing/product/{sn}/requests）。
     */
    private void publishRequest(String method, String tid, String bid, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", bid);
        envelope.put("tid", tid);
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("gateway", runtimeConfig.getDockSn());
        envelope.put("method", method);
        envelope.put("data", data);
        String topic = dockTopicSchema.topic(dockTopicSchema.requests(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已发送 requests: method={}, tid={}", method, tid);
    }

    /**
     * 构造 live_capacity（直播能力）数据。
     * <p>live_capacity 是 pushMode=1 属性，DJI 文档规定随 state topic 上报。
     * 由 {@link #publishDockState()} 合并到机场 state 消息中一次性推送，避免连发两条 state。</p>
     * <p>核实依据：DJI Cloud API 直播功能文档「直播能力更新」：
     * - Topic: thing/product/{device_sn}/state
     * - 数据结构: live_capacity → available_video_number / coexist_video_number_max / device_list[] → camera_list[] → video_list[]
     * - 文档 URL: https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/live.html
     * </p>
     */
    private Map<String, Object> buildLiveCapacity() {
        // 相机索引随机型解析（TC-LIVE-026/TC-PAYLOAD-026）：与 drone OSD payloads 的 payload_index 同源，
        // 硬编码 165-0-7（机场相机）会导致平台拼 video_id 与实际推流相机不符
        String cameraIndex = DefaultCameraResolver.requireDefaultCameraIndex(runtimeConfig.getDroneType(), "live_capacity");

        // 视频流信息（video_list 元素）
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("video_index", "normal-0");
        video.put("video_type", "normal");
        video.put("switchable_video_types", List.of("normal"));

        // 相机信息（camera_list 元素）
        Map<String, Object> cameraInfo = new LinkedHashMap<>();
        cameraInfo.put("camera_index", cameraIndex);
        cameraInfo.put("available_video_number", 1);
        cameraInfo.put("coexist_video_number_max", 1);
        cameraInfo.put("video_list", List.of(video));

        // 子设备信息（device_list 元素）
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("sn", runtimeConfig.getDroneSn());
        device.put("available_video_number", 1);
        device.put("coexist_video_number_max", 1);
        device.put("camera_list", List.of(cameraInfo));

        // 直播能力
        Map<String, Object> liveCapacity = new LinkedHashMap<>();
        liveCapacity.put("available_video_number", 1);
        liveCapacity.put("coexist_video_number_max", 1);
        liveCapacity.put("device_list", List.of(device));
        return liveCapacity;
    }

    /**
     * 推送机场 state 属性到 thing/product/{dockSn}/state。
     * <p>机场上线后调用，推送所有 pushMode=1 的机场属性初始值。
     * 对齐 DJI 设备属性文档，pushMode=1 的属性在状态变化时上报：
     * https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/properties.html</p>
     */
    public void publishDockState() {
        if (!state.isOnline()) {
            log.warn("设备未上线，跳过 dock state 推送");
            return;
        }
        if (!mqtt.isConnected()) {
            log.warn("MQTT 未连接，跳过 dock state 推送");
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();

        // 固件相关（pushMode=1, r）
        data.put(StateField.FIRMWARE_VERSION.fieldName(), "0.0.0.0");           // 固件版本
        data.put(StateField.FIRMWARE_UPGRADE_STATUS.fieldName(), 0);             // 未升级
        data.put(StateField.COMPATIBLE_STATUS.fieldName(), 0);                   // 不需要一致性升级

        // 运行信息（pushMode=1, r）
        data.put(StateField.ACC_TIME.fieldName(), 0);                            // 机场累计运行时长（s）

        // 用户配置（pushMode=1, rw）— 从 DeviceState 读取，反映 property/set 设置的值
        // air_transfer_enable 仅 Dock2/Dock3 支持（DJI 文档 Dock1 properties 列表无此字段）
        if (runtimeConfig.getDockType() != DockModel.DOCK1) {
            data.put(StateField.AIR_TRANSFER_ENABLE.fieldName(), state.isAirTransferEnable());
        }
        data.put(StateField.USER_EXPERIENCE_IMPROVEMENT.fieldName(), state.getUserExperienceImprovement());
        data.put(StateField.SILENT_MODE.fieldName(), state.getSilentMode());

        // 以下字段仅 Dock2/Dock3 支持（DJI 文档 Dock1 properties 列表无此字段）
        if (runtimeConfig.getDockType() != DockModel.DOCK1) {
            // RTK 标定源（pushMode=1, r）
            Map<String, Object> rtcmInfo = new LinkedHashMap<>();
            rtcmInfo.put("mount_point", "");
            rtcmInfo.put("port", "");
            rtcmInfo.put("host", "");
            rtcmInfo.put("rtcm_device_type", 1);                // 机场
            rtcmInfo.put("source_type", 0);                     // 未标定
            data.put(StateField.RTCM_INFO.fieldName(), rtcmInfo);

            // 图传连接拓扑（pushMode=1, r）
            Map<String, Object> centerNode = new LinkedHashMap<>();
            centerNode.put("sdr_id", 0);
            centerNode.put("sn", runtimeConfig.getDroneSn());
            Map<String, Object> wirelessLinkTopo = new LinkedHashMap<>();
            // secret_code: 28 元素数组（全 0）
            List<Integer> secretCode = new ArrayList<>();
            for (int i = 0; i < 28; i++) {
                secretCode.add(0);
            }
            wirelessLinkTopo.put("secret_code", secretCode);
            wirelessLinkTopo.put("center_node", centerNode);
            wirelessLinkTopo.put("leaf_nodes", List.of());
            data.put(StateField.WIRELESS_LINK_TOPO.fieldName(), wirelessLinkTopo);

            // 4G Dongle 信息（pushMode=1, r）
            Map<String, Object> dongleInfo = new LinkedHashMap<>();
            dongleInfo.put("imei", "");
            dongleInfo.put("dongle_type", 10);                  // 支持 eSIM 的新 Dongle
            dongleInfo.put("eid", "");
            dongleInfo.put("esim_activate_state", 0);            // 未知
            dongleInfo.put("sim_card_state", 1);                 // 已插入
            dongleInfo.put("sim_slot", 2);                       // eSIM
            dongleInfo.put("esim_infos", List.of());
            Map<String, Object> simInfo = new LinkedHashMap<>();
            simInfo.put("telecom_operator", 0);                  // 未知
            simInfo.put("sim_type", 0);                          // 未知
            simInfo.put("iccid", "");
            dongleInfo.put("sim_info", simInfo);
            data.put(StateField.DONGLE_INFOS.fieldName(), List.of(dongleInfo));
        }

        // 直播状态推送（pushMode=1, r）— 无在推视频流时为空数组
        data.put(StateField.LIVE_STATUS.fieldName(), List.of());

        // Dock1 特有：drone_authority_info.payloads（pushMode=1，负载控制权状态）
        if (runtimeConfig.getDockType() == DockModel.DOCK1) {
            Map<String, Object> droneAuthorityInfo = new LinkedHashMap<>();
            PayloadType camera = DefaultCameraResolver.defaultCameraFor(runtimeConfig.getDroneType());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("control_source", "A");
            payload.put("payload_index", DefaultCameraResolver.requireCameraIndex(camera, runtimeConfig.getDroneType(), "drone_authority_info.payloads"));
            payload.put("sn", "simulated-payload-001");
            droneAuthorityInfo.put("payloads", List.of(payload));
            data.put(StateField.DRONE_AUTHORITY_INFO.fieldName(), droneAuthorityInfo);
        }

        // 直播能力（pushMode=1, r）— 合并到 dock state 一次性上报，对齐 DJI 文档「state 属性变化时上报」
        data.put(StateField.LIVE_CAPACITY.fieldName(), buildLiveCapacity());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("data", data);
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("gateway", runtimeConfig.getDockSn());

        String topic = dockTopicSchema.topic(dockTopicSchema.state(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
        log.info("已推送 dock state（{} 属性，含 live_capacity）", data.size());
    }

    /**
     * 推送飞行器 state 属性到 thing/product/{droneSn}/state。
     * <p>飞行器从休眠切换为激活时调用，推送所有 pushMode=1 的飞行器属性初始值。
     * 对齐 DJI 设备管理时序图中「设备（飞行器）属性推送 Topic: thing/product/{device_sn}/state」。</p>
     * <p>核实依据：DJI 飞行器设备属性文档，pushMode=1 的属性在状态变化时上报：
     * https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/properties.html</p>
     */
    public void publishDroneState() {
        if (!state.isOnline()) {
            log.warn("设备未上线，跳过 drone state 推送");
            return;
        }
        if (!mqtt.isConnected()) {
            log.warn("MQTT 未连接，跳过 drone state 推送");
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        DroneModel droneType = runtimeConfig.getDroneType();

        // payloads — 负载状态（pushMode=1）
        // payload_index 按机型动态获取（M30→52-0-0, M30T→53-0-0, M3D→80-0-0, M4D→98-0-0 等）
        PayloadType droneCamera = DefaultCameraResolver.defaultCameraFor(droneType);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("control_source", "A");
        payload.put("payload_index", DefaultCameraResolver.requireCameraIndex(droneCamera, droneType, "state.payloads"));
        payload.put("firmware_version", "0.0.0.0");
        payload.put("sn", runtimeConfig.getDroneSn());
        data.put(StateField.PAYLOADS.fieldName(), List.of(payload));

        // wpmz_version — 航线解析库版本号
        data.put(StateField.WPMZ_VERSION.fieldName(), "1.0.2");

        // commander_mode_lost_action — 指点飞行失控动作
        data.put(StateField.COMMANDER_MODE_LOST_ACTION.fieldName(), 0);

        // commander_flight_mode — 指点飞行模式设置值（pushMode=1, rw）
        data.put(StateField.COMMANDER_FLIGHT_MODE.fieldName(), 0);

        // current_commander_flight_mode — 指点飞行模式当前值
        data.put(StateField.CURRENT_COMMANDER_FLIGHT_MODE.fieldName(), 0);

        // commander_flight_height — 指点飞行高度
        data.put(StateField.COMMANDER_FLIGHT_HEIGHT.fieldName(), 0.0);

        // mode_code_reason — 飞行器进入当前状态的原因
        data.put(StateField.MODE_CODE_REASON.fieldName(), 0);

        // firmware_version — 固件版本
        // M400 Pilot 模式 pushMode=0（OSD 主题上报），state 主题不上报顶层 firmware_version
        if (droneType != DroneModel.M400) {
            data.put(StateField.FIRMWARE_VERSION.fieldName(), "0.0.0.0");
        }

        // compatible_status — 固件一致性
        data.put(StateField.COMPATIBLE_STATUS.fieldName(), 0);

        // firmware_upgrade_status — 固件升级状态
        data.put(StateField.FIRMWARE_UPGRADE_STATUS.fieldName(), 0);

        // home_longitude / home_latitude — Home 点位置
        data.put(StateField.HOME_LONGITUDE.fieldName(), runtimeConfig.getLocationLongitude());
        data.put(StateField.HOME_LATITUDE.fieldName(), runtimeConfig.getLocationLatitude());

        // control_source — 当前控制源
        data.put(StateField.CONTROL_SOURCE.fieldName(), "A");

        // low_battery_warning_threshold — 低电量告警
        data.put(StateField.LOW_BATTERY_WARNING_THRESHOLD.fieldName(), 50);

        // serious_low_battery_warning_threshold — 严重低电量告警
        data.put(StateField.SERIOUS_LOW_BATTERY_WARNING_THRESHOLD.fieldName(), 20);

        // rth_mode / current_rth_mode — 返航高度模式（机场只支持设定高度=1）
        // rth_mode M3D/M4D/M400 文档有（M30 文档无此字段），current_rth_mode 各版共有
        data.put(StateField.CURRENT_RTH_MODE.fieldName(), 1);
        if (droneType == DroneModel.M3D || droneType == DroneModel.M3TD
                || droneType == DroneModel.M4D || droneType == DroneModel.M4TD
                || droneType == DroneModel.M400) {
            data.put(StateField.RTH_MODE.fieldName(), 1);
        }

        // psdk_ui_resource — psdk ui 资源包
        data.put(StateField.PSDK_UI_RESOURCE.fieldName(), List.of());

        // psdk_widget_values — psdk 负载设备属性值（pushMode=1, r）
        // TC-ONLINE-007-PSDK：Dock 模式（M30/M3D/M4D）填充喊话器设备标识，让平台通过 state topic 获悉飞行器挂载的 PSDK 设备
        // M400 Pilot 属性列表未列此字段，保持空数组（TC-ONLINE-007-A）
        data.put(StateField.PSDK_WIDGET_VALUES.fieldName(), buildPsdkWidgetValues(droneType));

        // {type-subtype-gimbalindex} / type_subtype_gimbalindex 的 pushMode=1 子字段
        // M30 旧版方式：payload_index（pushMode=1）+ thermal_supported_palette_styles（pushMode=1, 仅 thermal）
        // M3D/M4D 升级方式：thermal_supported_palette_styles（pushMode=1, 仅 thermal）
        boolean isThermalDrone = droneType == DroneModel.M30T || droneType == DroneModel.M3TD || droneType == DroneModel.M4TD;
        if (droneType == DroneModel.M30 || droneType == DroneModel.M30T) {
            // M30 旧版方式：以负载索引为 key
            PayloadType camera = DefaultCameraResolver.defaultCameraFor(droneType);
            if (camera != null) {
                Map<String, Object> payloadStruct = new LinkedHashMap<>();
                payloadStruct.put("payload_index", camera.cameraIndex());
                if (isThermalDrone) {
                    payloadStruct.put("thermal_supported_palette_styles", List.of(0, 1, 2, 3, 5, 6, 8, 11, 12, 13));
                }
                data.put(camera.cameraIndex(), payloadStruct);
            }
        } else if (isThermalDrone && (droneType == DroneModel.M3D || droneType == DroneModel.M3TD
                || droneType == DroneModel.M4D || droneType == DroneModel.M4TD)) {
            // M3D/M4D：key 与 M30 分支一致，用负载索引枚举值（文档 Column 名 type_subtype_gimbalindex 是占位符，
            // 描述"与字段 payload_index 保持一致"，禁止字面量作 key，TC-PAYLOAD-027）
            PayloadType camera = DefaultCameraResolver.defaultCameraFor(droneType);
            if (camera != null) {
                Map<String, Object> gimbalStruct = new LinkedHashMap<>();
                gimbalStruct.put("thermal_supported_palette_styles", List.of(0, 1, 2, 3, 5, 6, 8, 11, 12, 13));
                data.put(camera.cameraIndex(), gimbalStruct);
            }
        }

        // M3D/M3TD/M4D/M4TD 特有：wireless_link_topo（pushMode=1, r）— 图传连接拓扑
        // 核实依据：M3D/M4D properties 文档 wireless_link_topo pushMode=1，应在 state topic 上报
        // M30 文档无此字段
        if (droneType == DroneModel.M4D || droneType == DroneModel.M4TD
                || droneType == DroneModel.M3D || droneType == DroneModel.M3TD) {
            Map<String, Object> centerNode = new LinkedHashMap<>();
            centerNode.put("sdr_id", 0);
            centerNode.put("sn", runtimeConfig.getDroneSn());
            Map<String, Object> wirelessLinkTopo = new LinkedHashMap<>();
            // secret_code: 28 元素数组（全 0）
            List<Integer> secretCode = new ArrayList<>();
            for (int i = 0; i < 28; i++) {
                secretCode.add(0);
            }
            wirelessLinkTopo.put("secret_code", secretCode);
            wirelessLinkTopo.put("center_node", centerNode);
            // leaf_nodes：连接的机场对频信息（空数组，单机场场景）
            wirelessLinkTopo.put("leaf_nodes", List.of());
            data.put(StateField.WIRELESS_LINK_TOPO.fieldName(), wirelessLinkTopo);
        }

        // M400 Pilot 特有字段（pushMode=1）
        // 核实依据：M400 Pilot 设备属性列表第一部分+第二部分
        // 待真机验证：offline_map_enable/dongle_infos/camera_watermark_settings 是否 M400 特有（其他机型文档未核实）
        if (droneType == DroneModel.M400) {
            // offline_map_enable — 离线地图开关（pushMode=1, r）
            data.put(StateField.OFFLINE_MAP_ENABLE.fieldName(), 0);  // 0=关闭

            // dongle_infos — 4G Dongle 信息（pushMode=1, r）
            List<Map<String, Object>> dongleInfos = new ArrayList<>();
            Map<String, Object> dongle = new LinkedHashMap<>();
            dongle.put("imei", "000000000000000");
            dongle.put("dongle_type", 10);        // 10=支持 eSIM 的新 Dongle
            dongle.put("eid", "00000000000000000000000000000000");
            dongle.put("esim_activate_state", 1); // 1=已激活
            dongle.put("sim_card_state", 1);      // 1=已插入
            dongle.put("sim_slot", 2);            // 2=eSIM
            // esim_infos — eSIM 信息数组
            List<Map<String, Object>> esimInfos = new ArrayList<>();
            Map<String, Object> esim = new LinkedHashMap<>();
            esim.put("telecom_operator", 1);      // 1=移动
            esim.put("enabled", true);
            esim.put("iccid", "0000000000000000000");
            esimInfos.add(esim);
            dongle.put("esim_infos", esimInfos);
            // sim_info — 实体 SIM 卡信息
            Map<String, Object> simInfo = new LinkedHashMap<>();
            simInfo.put("telecom_operator", 0);   // 0=未知
            simInfo.put("sim_type", 0);           // 0=未知
            simInfo.put("iccid", "");
            dongle.put("sim_info", simInfo);
            dongleInfos.add(dongle);
            data.put(StateField.DONGLE_INFOS.fieldName(), dongleInfos);

            // camera_watermark_settings — 相机水印设置（pushMode=1, rw）
            Map<String, Object> cameraWatermarkSettings = new LinkedHashMap<>();
            cameraWatermarkSettings.put("global_enable", 0);             // 0=关闭
            cameraWatermarkSettings.put("drone_type_enable", 0);
            cameraWatermarkSettings.put("drone_sn_enable", 0);
            cameraWatermarkSettings.put("datetime_enable", 0);
            cameraWatermarkSettings.put("gps_enable", 0);
            cameraWatermarkSettings.put("user_custom_string_enable", 0);
            cameraWatermarkSettings.put("user_custom_string", "");
            cameraWatermarkSettings.put("layout", 0);                    // 0=左上
            data.put(StateField.CAMERA_WATERMARK_SETTINGS.fieldName(), cameraWatermarkSettings);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("data", data);
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());

        String topic = dockTopicSchema.topic(dockTopicSchema.state(), runtimeConfig.getDroneSn());
        mqtt.publishJson(topic, envelope);
        log.info("已推送 drone state（{} 属性）", data.size());
    }

    /**
     * 构造 psdk_widget_values（PSDK 负载设备属性值，pushMode=1, r）。
     * <p>对齐 DJI M30 properties 文档：数组元素含 psdk_index/psdk_name/psdk_sn。
     * Dock 模式（M30/M3D/M4D）填充喊话器设备标识，与 DRC 通道 {@code drc_psdk_state_info} 上报保持一致
     * （见 {@link ltd.cdmi.hivemind.simulator.device.DeviceSimulator#publishPsdkAndAiEvents}）。</p>
     * <p>M400 Pilot 属性列表未列此字段，返回空数组（TC-ONLINE-007-A）。</p>
     *
     * @param droneType 飞行器机型
     * @return PSDK 设备属性值数组；M400 返回空数组
     */
    static List<Map<String, Object>> buildPsdkWidgetValues(DroneModel droneType) {
        // M400 Pilot 属性列表未列 psdk_widget_values，返回空数组
        if (droneType == DroneModel.M400) {
            return List.of();
        }
        // 喊话器设备标识（常量定义见 PsdkConstants，与 DRC 通道 drc_psdk_state_info 共用同一真相源）
        Map<String, Object> speaker = new LinkedHashMap<>();
        speaker.put("psdk_index", PsdkConstants.SPEAKER_INDEX);
        speaker.put("psdk_name", PsdkConstants.SPEAKER_NAME);
        speaker.put("psdk_sn", PsdkConstants.SPEAKER_SN);
        return List.of(speaker);
    }
}
