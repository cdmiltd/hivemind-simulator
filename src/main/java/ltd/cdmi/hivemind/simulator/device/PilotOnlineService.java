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
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.StatusMethod;
import ltd.cdmi.dji.cloudapi.sdk.telemetry.StateField;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.osd.DroneStateBuilder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.MapElementSimulator;
import ltd.cdmi.hivemind.simulator.handler.SituationAwarenessSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.PilotTopicSchema;
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
 * Pilot to Cloud 上线流程模拟。
 * <p>Pilot 模式跳过 JSBridge + 设备绑定流程，MQTT 连接成功后直接发送 update_topo 上线。
 * <p>与 {@link DockOnlineService} 的差异：
 * <ul>
 *   <li>无 config/airport_bind_status/airport_organization_get/airport_organization_bind 注册流程</li>
 *   <li>网关设备为遥控器（domain=2），使用 controllerSn/controllerType</li>
 *   <li>飞行器始终激活（遥控器直接控制，无收纳概念）</li>
 *   <li>不需要 requests_reply 监听器（无注册流程）</li>
 * </ul>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/pilot-feature-set/pilot-access-to-cloud.html">Pilot 上云功能介绍</a>
 */
@Service
public class PilotOnlineService {

    private static final Logger log = LoggerFactory.getLogger(PilotOnlineService.class);
    /** 等待云端回复超时时间（秒） */
    private static final long REPLY_TIMEOUT_SECONDS = 10;

    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final ObjectMapper objectMapper;
    private final RuntimeConfig runtimeConfig;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final List<DroneStateBuilder> stateBuilders;
    private final TopicSchema topicSchema;
    private final MapElementSimulator mapElementSimulator;
    private final SituationAwarenessSimulator situationAwarenessSimulator;

    /** tid → CompletableFuture，用于等待 status_reply */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingReplies = new ConcurrentHashMap<>();

    /** 已注册 status_reply 监听器的 controllerSn（controllerSn 变化时需重新注册） */
    private volatile String registeredReplyControllerSn;

    public PilotOnlineService(MqttClientManager mqtt, DeviceState state,
                              ObjectMapper objectMapper, RuntimeConfig runtimeConfig,
                              DiagnosticLogRecorder diagnosticRecorder,
                              List<DroneStateBuilder> stateBuilders,
                              MapElementSimulator mapElementSimulator,
                              SituationAwarenessSimulator situationAwarenessSimulator) {
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.runtimeConfig = runtimeConfig;
        this.diagnosticRecorder = diagnosticRecorder;
        this.stateBuilders = stateBuilders;
        this.topicSchema = new PilotTopicSchema(runtimeConfig.getControllerType());
        this.mapElementSimulator = mapElementSimulator;
        this.situationAwarenessSimulator = situationAwarenessSimulator;
        ensureReplyListeners();
    }

    /**
     * 确保 status_reply 监听器已注册（controllerSn 变化时重新注册）。
     * <p>切换 Pilot 设备类型时 {@link RuntimeConfig#setControllerType} 会自动更新 controllerSn，
     * 此方法确保监听器始终绑定当前 controllerSn 对应的 topic。</p>
     * <p>Pilot 模式无注册流程，不需要 requests_reply 监听器。</p>
     */
    private void ensureReplyListeners() {
        String controllerSn = runtimeConfig.getControllerSn();
        if (controllerSn.equals(registeredReplyControllerSn)) {
            return;
        }
        mqtt.addListener(topicSchema.topic(topicSchema.statusReply(), controllerSn), this::handleReply);
        registeredReplyControllerSn = controllerSn;
        log.info("PilotOnlineService 已注册 status_reply 监听器: controllerSn={}", controllerSn);
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
     * 执行 Pilot 模式上线流程。
     * <p>时序（对齐 DJI Pilot 上云规范）：
     * <ol>
     *   <li>update_topo 上线（网关=遥控器，sub_devices=[飞行器]）</li>
     *   <li>上报 live_capacity</li>
     * </ol>
     * 跳过 config/bind/org 注册流程（Pilot 通过 JSBridge + HTTPS 完成设备绑定，模拟器从 MQTT 连接开始）。
     * <p>对齐 DJI 行为：update_topo 超时不停止上线流程。
     * @return OnlineResult，success=true 表示上线成功
     */
    public DockOnlineService.OnlineResult online() {
        if (state.isOnline()) {
            log.warn("设备已在线，忽略重复上线");
            return DockOnlineService.OnlineResult.ok();
        }
        if (!mqtt.isConnected()) {
            log.warn("MQTT 未连接，无法执行 Pilot 上线流程");
            return DockOnlineService.OnlineResult.fail(DiagnosticCode.SIMULATOR_MQTT_NOT_CONNECTED, DockOnlineService.OnlineResult.STEP_MQTT);
        }
        try {
            log.info("Pilot 模式上线：跳过注册流程，直接 update_topo");
            if (!sendUpdateTopo()) {
                log.warn("update_topo 失败，停止上线流程");
                return DockOnlineService.OnlineResult.fail(DiagnosticCode.PLATFORM_NO_REPLY, DockOnlineService.OnlineResult.STEP_UPDATE_TOPO);
            }
            state.setOnline(true);
            // Pilot 模式飞行器始终激活（遥控器直接控制，无收纳概念）
            state.setDroneActivated(true);
            publishLiveCapacity();
            publishControllerState();
            publishDroneState();
            // Pilot 上线后拉取地图元素列表（DJI 时序图：首次登录拉取全部地图元素）
            mapElementSimulator.init();
            // Pilot 首次上线后主动获取设备拓扑列表（DJI 文档：获取工作空间下所有设备列表及拓扑）
            situationAwarenessSimulator.init();
            log.info("Pilot 上线流程完成: controllerSn={}, droneSn={}",
                    runtimeConfig.getControllerSn(), runtimeConfig.getDroneSn());
            return DockOnlineService.OnlineResult.ok();
        } catch (Exception e) {
            log.error("Pilot 上线流程异常: {}", e.getMessage(), e);
            return DockOnlineService.OnlineResult.fail(DiagnosticCode.SIMULATOR_PARSE_BUG);
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
        state.setOnline(false);
        // Pilot 下线时清理地图元素模拟器
        mapElementSimulator.destroy();

        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        // RC Plus 2 行业版差异（TC-ONLINE-016）：网关设备不上报 domain
        if (runtimeConfig.getControllerType() != RcModel.RC_PLUS_2) {
            data.put("domain", String.valueOf(runtimeConfig.getControllerType().domain()));
        }
        data.put("type", runtimeConfig.getControllerType().type());
        data.put("sub_type", runtimeConfig.getControllerType().subType());
        data.put("device_secret", "secret");
        data.put("nonce", "nonce");
        data.put("sub_devices", List.of());
        data.put("thing_version", runtimeConfig.getThingVersion());
        publishStatus(StatusMethod.UPDATE_TOPO.methodName(), tid, bid, data);

        log.info("Pilot 设备已下线: controllerSn={}", runtimeConfig.getControllerSn());
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
     *
     * @return true 表示继续上线，false 表示停止上线
     */
    /** 构造 update_topo 上线报文数据（Pilot 模式飞行器始终激活，sub_devices 始终包含飞行器） */
    private Map<String, Object> buildUpdateTopoData() {
        RcModel controllerType = runtimeConfig.getControllerType();
        DroneModel droneType = runtimeConfig.getDroneType();
        boolean isRcPlus2 = (controllerType == RcModel.RC_PLUS_2);

        // DJI update_topo: data 顶层包含网关设备的 domain（string）、type（int）、sub_type（int）、
        // device_secret（text）、nonce（text）、thing_version（text）
        // RC Plus 2 行业版差异（TC-ONLINE-016）：网关设备不上报 domain，子设备不上报 domain 和 index
        // Pilot 模式飞行器始终激活，sub_devices 始终包含飞行器
        Map<String, Object> data = new LinkedHashMap<>();
        if (!isRcPlus2) {
            data.put("domain", String.valueOf(controllerType.domain()));
        }
        data.put("type", controllerType.type());
        data.put("sub_type", controllerType.subType());
        data.put("device_secret", "secret");
        data.put("nonce", "nonce");
        data.put("sub_devices", List.of(
                buildSubDeviceData(droneType, isRcPlus2)
        ));
        data.put("thing_version", runtimeConfig.getThingVersion());
        return data;
    }

    /**
     * 构造子设备（飞行器）的 update_topo 数据。
     * <p>RC Plus 2 行业版不上报 domain 和 index 字段（TC-ONLINE-016）。</p>
     */
    private Map<String, Object> buildSubDeviceData(DroneModel droneType, boolean isRcPlus2) {
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("sn", runtimeConfig.getDroneSn());
        if (!isRcPlus2) {
            sub.put("domain", String.valueOf(droneType.domain()));
        }
        sub.put("type", droneType.type());
        sub.put("sub_type", droneType.subType());
        if (!isRcPlus2) {
            sub.put("index", "A");
        }
        sub.put("device_secret", "secret");
        sub.put("nonce", "nonce");
        sub.put("thing_version", runtimeConfig.getThingVersion());
        return sub;
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
                    "平台未回复 status_reply（超时 " + REPLY_TIMEOUT_SECONDS + "s）");
            return true;
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
        String topic = topicSchema.topic(topicSchema.status(), runtimeConfig.getControllerSn());
        mqtt.publishJson(topic, envelope);
        log.info("已发送 status: method={}, tid={}", method, tid);
    }

    /**
     * 上报 live_capacity（直播能力），告知云端设备支持的直播视频流。
     * <p>topic: thing/product/{controllerSn}/state（Pilot 模式网关为遥控器）。
     * <p>核实依据：DJI Cloud API 直播功能文档「直播能力更新」：
     * <ul>
     *   <li>Topic: thing/product/{device_sn}/state</li>
     *   <li>数据结构: live_capacity → available_video_number / coexist_video_number_max / device_list[] → camera_list[] → video_list[]</li>
     *   <li>文档 URL: https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/live.html</li>
     * </ul>
     */
    private void publishLiveCapacity() {
        // 相机索引随机型解析（TC-LIVE-026/TC-PAYLOAD-026）：与 drone OSD payloads 的 payload_index 同源，
        // 硬编码 165-0-7（机场相机）会导致平台拼 video_id 与实际推流相机不符
        String cameraIndex = DefaultCameraResolver.requireDefaultCameraIndex(runtimeConfig.getDroneType(), "Pilot live_capacity");

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
        device.put("camera_list", List.of(camera));

        // 直播能力
        Map<String, Object> liveCapacity = new LinkedHashMap<>();
        liveCapacity.put("available_video_number", 1);
        liveCapacity.put("coexist_video_number_max", 1);
        liveCapacity.put("device_list", List.of(device));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(StateField.LIVE_CAPACITY.fieldName(), liveCapacity);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("gateway", runtimeConfig.getControllerSn());
        envelope.put("data", data);

        String topic = topicSchema.topic(topicSchema.state(), runtimeConfig.getControllerSn());
        mqtt.publishJson(topic, envelope);
        log.info("已上报 live_capacity（state topic）");
    }

    /**
     * 推送遥控器 state 属性到 thing/product/{controllerSn}/state。
     * <p>上线后调用，推送所有 pushMode=1 的遥控器属性初始值（live_capacity 已由 publishLiveCapacity 独立上报）。
     * <p>对齐 RC Plus 2 设备属性文档，pushMode=1 的属性在状态变化时上报：
     * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/dji-rc-plus-2/properties.html">RC Plus 2 设备属性</a></p>
     * <p>核实依据：用户提供的 RC Plus 2 行业版设备属性列表（pushMode=1 字段集）</p>
     */
    void publishControllerState() {
        Map<String, Object> data = new LinkedHashMap<>();

        // dongle_infos — 4G Dongle 信息（pushMode=1, r）
        data.put(StateField.DONGLE_INFOS.fieldName(), buildDongleInfos());

        // live_status — 网关当前整体直播状态推送（pushMode=1, r）
        // 无直播时为空数组
        data.put(StateField.LIVE_STATUS.fieldName(), List.of());

        // firmware_version — 固件版本（pushMode=1, r）
        data.put(StateField.FIRMWARE_VERSION.fieldName(), "0.0.0.0");

        // cloud_control_auth — 本遥控器授权云控列表（pushMode=1, r）
        // 无授权时为空数组
        data.put(StateField.CLOUD_CONTROL_AUTH.fieldName(), List.of());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("gateway", runtimeConfig.getControllerSn());
        envelope.put("data", data);

        String topic = topicSchema.topic(topicSchema.state(), runtimeConfig.getControllerSn());
        mqtt.publishJson(topic, envelope);
        log.info("已推送遥控器 state 属性（state topic）");
    }

    /**
     * 构造 4G Dongle 信息数组（dongle_infos）。
     * <p>结构与 M400 Pilot 模式 dongle_infos 一致（DJI Cloud API 通用 Dongle 信息结构）。</p>
     * <p>模拟值：1 个支持 eSIM 的新 Dongle，已激活，使用 eSIM（移动运营商）。</p>
     */
    private List<Map<String, Object>> buildDongleInfos() {
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
        return dongleInfos;
    }

    /**
     * 推送飞行器 state 属性到 thing/product/{droneSn}/state。
     * <p>上线后调用，推送所有 pushMode=1 的飞行器属性初始值。</p>
     * <p>通过 {@link DroneStateBuilder} 按机型区分 state 字段集：
     * <ul>
     *   <li>Mavic 3E/3T → {@link Mavic3StateBuilder}（含 firmware_version，pushMode=1）</li>
     *   <li>M400/M4E/M4T → {@link M4StateBuilder}（不含 firmware_version，含 commander_flight_*、rth_mode、offline_map_enable）</li>
     * </ul>
     * <p>找不到对应 Builder 时跳过 state 上报并记录警告（避免上报错误字段集）。</p>
     */
    void publishDroneState() {
        DroneModel droneType = runtimeConfig.getDroneType();
        DroneStateBuilder builder = null;
        for (DroneStateBuilder b : stateBuilders) {
            if (b.supports(droneType)) {
                builder = b;
                break;
            }
        }
        if (builder == null) {
            log.warn("未找到 {} 的 DroneStateBuilder，跳过飞行器 state 上报", droneType);
            return;
        }

        Map<String, Object> data = builder.buildDroneState(runtimeConfig);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("data", data);

        String topic = topicSchema.topic(topicSchema.state(), runtimeConfig.getDroneSn());
        mqtt.publishJson(topic, envelope);
        log.info("已推送飞行器 state 属性（state topic），builder={}", builder.aircraftFamily());
    }
}
