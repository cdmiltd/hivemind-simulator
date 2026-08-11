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

package ltd.cdmi.simulator.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.simulator.config.RuntimeConfig;
import ltd.cdmi.simulator.config.SimulatorProperties;
import ltd.cdmi.simulator.device.DeviceState;
import ltd.cdmi.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.simulator.diagnostic.ProtocolValidator;
import ltd.cdmi.simulator.mqtt.MqttClientManager;
import ltd.cdmi.simulator.mqtt.TopicConstants;
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

    public PropertySetHandler(SimulatorProperties props, MqttClientManager mqtt,
                              DeviceState state, ObjectMapper objectMapper,
                              DiagnosticLogRecorder diagnosticRecorder,
                              CoverageRecorder coverageRecorder,
                              RuntimeConfig runtimeConfig) {
        this.props = props;
        this.mqtt = mqtt;
        this.state = state;
        this.objectMapper = objectMapper;
        this.diagnosticRecorder = diagnosticRecorder;
        this.coverageRecorder = coverageRecorder;
        this.runtimeConfig = runtimeConfig;
    }

    @PostConstruct
    public void init() {
        String dockSn = props.device().dockSn();
        mqtt.addListener(TopicConstants.topic(TopicConstants.PROPERTY_SET, dockSn), this::handlePropertySet);
        log.info("PropertySetHandler 已注册监听: {}", TopicConstants.topic(TopicConstants.PROPERTY_SET, dockSn));
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

            // accessMode=rw 的属性：更新本地状态，下次 OSD 反映新值
            if (data.has("silent_mode")) {
                int val = data.get("silent_mode").asInt();
                state.setSilentMode(val);
                log.info("属性设置 silent_mode={}", val);
            }
            // accessMode=r 的属性（如 air_conditioner_state）：仅回复，不更新本地状态

            // 回 property/set_reply：data 回显被设置的属性键值对（DJI Cloud API 协议）
            // hivemind 据此确认属性设置成功；不应返回 {result:0} 这种 services 风格结构
            Map<String, Object> replyData;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> converted = objectMapper.convertValue(data, Map.class);
                replyData = converted != null ? converted : new LinkedHashMap<>();
            } catch (Exception ex) {
                DiagnosticCode code = ProtocolValidator.classifyException(ex);
                log.error("{} property/set 数据转换异常: {}", ProtocolValidator.logPrefix(code), ex.getMessage());
                diagnosticRecorder.record(code, "property_set", "数据转换异常: " + ex.getMessage());
                replyData = new LinkedHashMap<>();
            }

            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("tid", tid);
            reply.put("bid", bid);
            reply.put("timestamp", System.currentTimeMillis());
            reply.put("data", replyData);

            String replyTopic = TopicConstants.topic(TopicConstants.PROPERTY_SET_REPLY, props.device().dockSn());
            mqtt.publishJson(replyTopic, reply);
            log.info("已回复 property/set_reply: tid={}", tid);
        } catch (Exception e) {
            DiagnosticCode code = ProtocolValidator.classifyException(e);
            log.error("{} 处理 property/set 失败: {}", ProtocolValidator.logPrefix(code), e.getMessage(), e);
            diagnosticRecorder.record(code, "property_set", "处理失败: " + e.getMessage());
        }
    }
}
