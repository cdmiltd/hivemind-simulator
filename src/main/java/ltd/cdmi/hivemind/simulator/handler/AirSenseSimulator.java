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

import ltd.cdmi.dji.cloudapi.sdk.codec.MessageCodec;
import ltd.cdmi.dji.cloudapi.sdk.protocol.envelope.EventEnvelope;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.EventMethod;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AirSense 告警通知模拟器。
 * <p>支持通过 Web 控制台手动触发一次 AirSense 告警事件上报（method=airsense_warning），
 * 模拟附近载人飞机的 ADS-B 告警信息。</p>
 * <p>协议参考：DJI Cloud API AirSense 告警通知（Topic=thing/product/{gateway_sn}/events, Method=airsense_warning）。
 * 与 HMS 不同，airsense_warning 的 data 直接是<b>数组</b>（非对象包裹），且 need_reply=1（需平台回复）。</p>
 * <p>核实依据：[Dock1 wayline.html] Event airsense_warning</p>
 */
@Component
public class AirSenseSimulator {

    private static final Logger log = LoggerFactory.getLogger(AirSenseSimulator.class);

    private final MqttClientManager mqtt;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    public AirSenseSimulator(MqttClientManager mqtt, RuntimeConfig runtimeConfig, DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
    }

    /**
     * 触发 AirSense 告警通知。
     *
     * @param alerts 告警列表（支持一次上报多个航班）
     * @return 触发结果：成功时 success=true；失败时 success=false 且包含 code/message
     */
    public TriggerResult trigger(List<AirSenseAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return TriggerResult.fail("INVALID_ALERTS", "告警列表为空");
        }
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报 AirSense 告警");
        }

        publishAirSenseEvent(alerts);
        log.info("AirSense 告警已上报: count={}, icaos={}", alerts.size(),
                alerts.stream().map(AirSenseAlert::icao).toList());
        return TriggerResult.ok(alerts.size());
    }

    /**
     * 构造并发送 AirSense 告警事件报文。
     * <p>报文格式：
     * {@code {bid, tid, timestamp, need_reply:1, gateway, method:"airsense_warning",
     *   data:[{icao, warning_level, latitude, longitude, altitude, altitude_type,
     *         heading, relative_altitude, vert_trend, distance}]}}</p>
     * <p>注意：data 直接是数组（DJI 协议定义），非对象包裹。</p>
     */
    private void publishAirSenseEvent(List<AirSenseAlert> alerts) {
        List<Map<String, Object>> data = alerts.stream()
                .map(this::buildAlertItem)
                .toList();

        EventEnvelope envelope = EventEnvelope.of(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                EventMethod.AIRSENSE_WARNING, data, runtimeConfig.getDockSn());

        String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getDockSn());
        mqtt.publish(topic, MessageCodec.toJson(envelope));
    }

    /**
     * 构造单个航班告警项。
     * <p>字段顺序按 DJI Example 字母序排列（不影响 JSON 解析）。</p>
     */
    private Map<String, Object> buildAlertItem(AirSenseAlert alert) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("icao", alert.icao());
        item.put("warning_level", alert.warningLevel());
        item.put("latitude", alert.latitude());
        item.put("longitude", alert.longitude());
        item.put("altitude", alert.altitude());
        item.put("altitude_type", alert.altitudeType());
        item.put("heading", alert.heading());
        item.put("relative_altitude", alert.relativeAltitude());
        item.put("vert_trend", alert.vertTrend());
        item.put("distance", alert.distance());
        return item;
    }

    /** AirSense 告警参数（单个航班） */
    public record AirSenseAlert(
            String icao,              // ICAO 民用航空飞机地址
            int warningLevel,         // 告警等级 0-4（≥3 建议避让）
            double latitude,          // 飞机纬度
            double longitude,         // 飞机经度
            int altitude,             // 绝对高度（米）
            int altitudeType,         // 0=椭球高, 1=海拔高
            double heading,           // 航向角度（0=正北, 90=正东）
            int relativeAltitude,     // 航班相对无人机高度（米）
            int vertTrend,            // 0=不变, 1=上升, 2=下降
            int distance              // 航班与无人机水平距离（米）
    ) {}

    /** 触发结果 */
    public record TriggerResult(boolean success, String code, String message, int count) {
        public static TriggerResult ok(int count) {
            return new TriggerResult(true, null, null, count);
        }
        public static TriggerResult fail(String code, String message) {
            return new TriggerResult(false, code, message, 0);
        }
    }
}
