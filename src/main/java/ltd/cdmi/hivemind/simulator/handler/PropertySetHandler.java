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
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.dji.cloudapi.sdk.codec.DjiMessage;
import ltd.cdmi.dji.cloudapi.sdk.command.property.PropertySetRequest;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.ProtocolValidator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * property/set 下行应答处理器。
 * <p>收到云端 property/set 后回 property/set_reply（result=0），并更新本地状态。</p>
 */
@Component
public class PropertySetHandler {

    private static final Logger log = LoggerFactory.getLogger(PropertySetHandler.class);

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final ObjectMapper objectMapper;
    private final DiagnosticLogRecorder diagnosticRecorder;
    private final CoverageRecorder coverageRecorder;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    public PropertySetHandler(SimulatorProperties props, MqttClientManager mqtt,
                              DeviceState state, ObjectMapper objectMapper,
                              DiagnosticLogRecorder diagnosticRecorder,
                              CoverageRecorder coverageRecorder,
                              RuntimeConfig runtimeConfig,
                              DockTopicSchema dockTopicSchema) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.diagnosticRecorder = diagnosticRecorder;
        this.coverageRecorder = coverageRecorder;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
    }

    @PostConstruct
    public void init() {
        String dockSn = runtimeConfig.getDockSn();
        mqtt.addListener(dockTopicSchema.topic(dockTopicSchema.propertySet(), dockSn), this::handlePropertySet);
        log.info("PropertySetHandler 已注册监听: {}", dockTopicSchema.topic(dockTopicSchema.propertySet(), dockSn));

        // M-2 诊断日志：标量属性 set_reply 格式为推断（DJI 文档示例仅展示 struct 属性）
        String inference = "标量属性 set_reply 格式 {\"属性名\": {\"result\": 0}}：DJI 文档 property/set_reply 示例仅展示 struct 属性"
            + "（如 distance_limit_status.state → {\"result\": 0}），未明确标量属性（如 silent_mode）的 set_reply 格式。"
            + "模拟器按 struct 属性的叶子字段包 result 的逻辑推断标量属性同样包 result，待真机验证";
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE, "property_set_reply_scalar", inference);
        log.warn("[M-2] 标量属性 set_reply 格式为推断（DJI 文档仅展示 struct 属性示例），待真机验证");
    }

    private void handlePropertySet(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String tid = node.path("tid").asText();
            String bid = node.path("bid").asText();
            JsonNode data = node.path("data");

            // P-6/P-7：主动校验必填字段和字段类型（不阻塞流程，仅记录诊断）
            DiagnosticCode fieldError = ProtocolValidator.validateFields(node);
            if (fieldError != null) {
                log.error("{} property/set 消息字段校验失败: tid={}", ProtocolValidator.logPrefix(fieldError), tid);
                diagnosticRecorder.record(fieldError, "property_set", "字段校验失败: tid=" + tid);
            }

            // 覆盖率统计：property/set 无 method 字段，统一记 "property_set"（按当前 MQTT 地址归档）
            coverageRecorder.record(runtimeConfig.getMqttHost() + ":" + runtimeConfig.getMqttPort(), "property_set");

            // 用 SDK POJO 解析 data：PropertySetRequest 通过 @JsonAnyGetter + @JsonCreator(DELEGATING)
            // 将整个 data 对象反序列化为 flat map（properties），避免逐字段 JsonNode 手动提取。
            // buildSetReplyData 仍需 JsonNode 递归处理 struct 属性的子字段，故同时保留 data JsonNode。
            PropertySetRequest req = DjiMessage.parse(payload, PropertySetRequest.class).data();
            Map<String, Object> properties = req != null ? req.properties() : Map.of();

            // accessMode=rw 的属性：更新本地状态，下次 state topic 反映新值
            if (properties.containsKey("silent_mode")) {
                int val = ((Number) properties.get("silent_mode")).intValue();
                state.setSilentMode(val);
                log.info("属性设置 silent_mode={}", val);
            }
            if (properties.containsKey("air_transfer_enable")) {
                // air_transfer_enable 仅 Dock2/Dock3 支持（DJI 文档 Dock1 properties 列表无此字段）
                // Dock1 收到此 set 仍回复 result=0 但不更新状态
                if (runtimeConfig.getDockType() != DockModel.DOCK1) {
                    boolean val = (Boolean) properties.get("air_transfer_enable");
                    state.setAirTransferEnable(val);
                    log.info("属性设置 air_transfer_enable={}", val);
                } else {
                    log.warn("Dock1 不支持 air_transfer_enable，忽略状态更新");
                }
            }
            if (properties.containsKey("user_experience_improvement")) {
                int val = ((Number) properties.get("user_experience_improvement")).intValue();
                state.setUserExperienceImprovement(val);
                log.info("属性设置 user_experience_improvement={}", val);
            }
            // accessMode=r 的属性（如 air_conditioner_state）：仅回复，不更新本地状态

            // 回 property/set_reply：每个被设置的叶子字段用 {"result": 0} 替换原值（DJI Cloud API 协议）
            // 对齐 DJI 文档示例：set {"distance_limit_status": {"state": 1}}
            //   → set_reply {"distance_limit_status": {"state": {"result": 0}}}
            // 标量属性：set {"silent_mode": 1} → set_reply {"silent_mode": {"result": 0}}
            Map<String, Object> replyData = buildSetReplyData(data);

            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("tid", tid);
            reply.put("bid", bid);
            reply.put("timestamp", System.currentTimeMillis());
            reply.put("data", replyData);

            String replyTopic = dockTopicSchema.topic(dockTopicSchema.propertySetReply(), runtimeConfig.getDockSn());
            mqtt.publishJson(replyTopic, reply);
            log.info("已回复 property/set_reply: tid={}", tid);
        } catch (Exception e) {
            DiagnosticCode code = ProtocolValidator.classifyException(e);
            log.error("{} 处理 property/set 失败: {}", ProtocolValidator.logPrefix(code), e.getMessage(), e);
            diagnosticRecorder.record(code, "property_set", "处理失败: " + e.getMessage());
        }
    }

    /**
     * 构建 property/set_reply 的 data：每个被设置的叶子字段用 {"result": 0} 替换原值。
     * <p>对齐 DJI Cloud API 文档示例：
     * <pre>
     * set:     {"distance_limit_status": {"state": 1}, "silent_mode": 1}
     * reply:   {"distance_limit_status": {"state": {"result": 0}}, "silent_mode": {"result": 0}}
     * </pre>
     * struct 属性递归处理子字段，标量属性直接替换。
     * </p>
     */
    private Map<String, Object> buildSetReplyData(JsonNode data) {
        Map<String, Object> replyData = new LinkedHashMap<>();
        data.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                // struct 属性：递归处理子字段
                replyData.put(key, buildSetReplyData(value));
            } else {
                // 标量属性：替换为 {"result": 0}（0=成功）
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("result", 0);
                replyData.put(key, result);
            }
        });
        return replyData;
    }
}
