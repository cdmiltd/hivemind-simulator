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
import ltd.cdmi.dji.cloudapi.sdk.codec.MessageCodec;
import ltd.cdmi.dji.cloudapi.sdk.command.service.esdk.CustomDataTransmissionToEsdkRequest;
import ltd.cdmi.dji.cloudapi.sdk.protocol.envelope.EventEnvelope;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.EventMethod;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ESDK 互联互通事件模拟（Dock1/Dock2/Dock3 [esdk-transmit-custom-data.html]）。
 *
 * <p>ESDK（Edge SDK）自定义消息传输，与 PSDK 互联互通结构相同但使用独立的 SDK 通道：
 * <ul>
 *   <li>custom_data_transmission_to_esdk（Service 下行）：cloud→ESDK 自定义消息，记录 value，返回 result=0</li>
 *   <li>custom_data_transmission_from_esdk（Event 上行）：ESDK→cloud 自定义消息，need_reply=0（DJI 文档未标注，记录 M-2 诊断日志）</li>
 * </ul>
 *
 * <p>Data 结构：{@code {value: text}}（length < 256 字节）。
 */
@Component
public class EsdkSimulator {

    private static final Logger log = LoggerFactory.getLogger(EsdkSimulator.class);

    /** ESDK 同步 Service 指令集 */
    private static final Set<String> ESDK_SERVICE_METHODS = Set.of("custom_data_transmission_to_esdk");

    private final MqttClientManager mqtt;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    /** 最近一次 cloud→ESDK 自定义消息内容 */
    private volatile String lastCustomData;

    public EsdkSimulator(MqttClientManager mqtt, RuntimeConfig runtimeConfig,
                         DiagnosticLogRecorder diagnosticRecorder,
                         DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;

        // M-2 诊断日志：custom_data_transmission_from_esdk need_reply 值未在 DJI 文档中标注
        diagnosticRecorder.record(DiagnosticCode.MONITOR_SIMULATOR_INFERENCE,
                "custom_data_transmission_from_esdk",
                "DJI esdk-transmit-custom-data.html 文档未标注 need_reply 值。"
                        + "模拟器遵循现有事件设置使用 need_reply=0（单向通知），待真机验证。");
    }

    /** 判断 method 是否属于 ESDK Service 指令 */
    public static boolean isEsdkServiceMethod(String method) {
        return ESDK_SERVICE_METHODS.contains(method);
    }

    /** 处理 ESDK Service 指令，返回 services_reply 的 output */
    public Map<String, Object> handleService(String method, JsonNode data) {
        if (!ESDK_SERVICE_METHODS.contains(method)) {
            throw new IllegalArgumentException("Unsupported ESDK service method: " + method);
        }
        return handleCustomDataToEsdk(data);
    }

    /** custom_data_transmission_to_esdk：记录 cloud→ESDK 自定义消息内容，返回 result=0 */
    private Map<String, Object> handleCustomDataToEsdk(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), CustomDataTransmissionToEsdkRequest.class);
        String value = req.value();
        lastCustomData = value;
        log.info("custom_data_transmission_to_esdk: value={}", value);
        return Map.of("result", 0);
    }

    /**
     * 触发 custom_data_transmission_from_esdk 事件（ESDK→cloud 自定义消息推送）。
     * <p>data 结构：{@code {value: text}}（length < 256）。
     * need_reply=0（DJI 文档未标注，遵循现有事件设置，记录 M-2 诊断日志）。</p>
     *
     * @param value 数据内容（长度 < 256）
     * @return 触发结果
     */
    public TriggerResult triggerCustomDataFromEsdk(String value) {
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报 ESDK 事件");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", value);
        publishEvent(EventMethod.CUSTOM_DATA_TRANSMISSION_FROM_ESDK, data);
        log.info("custom_data_transmission_from_esdk 已上报: value={}", value);
        return TriggerResult.ok();
    }

    private void publishEvent(EventMethod method, Map<String, Object> data) {
        EventEnvelope envelope = EventEnvelope.of(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                method, data, runtimeConfig.getDockSn());

        String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getDockSn());
        mqtt.publish(topic, MessageCodec.toJson(envelope));
    }

    // ==================== REST API 辅助 ====================

    /**
     * 查询最近一次 cloud→ESDK 自定义消息内容。
     *
     * @return 自定义消息 value，未收到时为 null
     */
    public String getLastCustomData() {
        return lastCustomData;
    }

    /** 触发结果（与 PsdkSimulator.TriggerResult 结构一致） */
    public record TriggerResult(boolean success, String code, String message) {
        public static TriggerResult ok() {
            return new TriggerResult(true, null, null);
        }
        public static TriggerResult fail(String code, String message) {
            return new TriggerResult(false, code, message);
        }
    }
}
