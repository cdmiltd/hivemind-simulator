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

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HMS 健康告警模拟器。
 * <p>支持通过 Web 控制台手动触发一次 HMS 事件上报（method=hms），
 * 上报内容为当前所选异常的全量告警列表。</p>
 * <p>协议参考：DJI Cloud API 健康告警（Topic=thing/product/{gateway_sn}/events, Method=hms）。
 * HMS 上报的是<b>全量告警信息</b>——上一次告警在本次消失即视为解除。</p>
 * <p>文案 Key 拼接规则：
 * <ul>
 *   <li>机场告警：{@code dock_tip_{code}}</li>
 *   <li>飞行器告警：{@code fpv_tip_{code}}（in_the_sky=0）或 {@code fpv_tip_{code}_in_the_sky}（in_the_sky=1）</li>
 * </ul>
 * 文案查询文件：{@code main/resources/hms.json}。</p>
 */
@Component
public class HmsSimulator {

    private static final Logger log = LoggerFactory.getLogger(HmsSimulator.class);

    /** HMS 告警 module 字段固定值：3=hms（DJI 协议定义） */
    private static final int MODULE_HMS = 3;

    /** 飞行器"在空中"对应的 mode_code 集合（4=自动起飞,5=航线飞行,9=自动返航,10=自动降落,13=返航降落） */
    private static final List<Integer> DRONE_IN_SKY_MODES = List.of(4, 5, 9, 10, 13);

    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    public HmsSimulator(MqttClientManager mqtt, DeviceState state, RuntimeConfig runtimeConfig,
                        DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.state = state;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
    }

    /**
     * 异常类型枚举。前端传入的 type 字符串必须与枚举常量名一致。
     * <p>每个枚举包含：原始 code、告警等级 level、是否及时性 imminent、设备归属（机场/飞行器）。</p>
     * <ul>
     *   <li>level: 0=通知, 1=提醒, 2=警告</li>
     *   <li>imminent: 1=及时性告警（如风过大，会随风减小自动消失）, 0=否</li>
     * </ul>
     */
    public enum AlarmType {
        /** 风速过大（≥9 m/s），无法执行飞行任务（机场告警，及时性） */
        WIND_HIGH("0x160900BF", 2, 1, DeviceScope.DOCK),
        /** 雨量过大，无法执行飞行任务（机场告警，及时性） */
        RAIN_HEAVY("0x19114800", 2, 1, DeviceScope.DOCK),
        /** 图传信号弱（机场告警，及时性） */
        SIGNAL_WEAK("0x17000004", 1, 1, DeviceScope.DOCK),
        /** 无图传信号（机场告警，及时性） */
        SIGNAL_LOST("0x17000001", 2, 1, DeviceScope.DOCK),
        /** 飞行器当前飞行高度超过安全飞行高度（飞行器告警） */
        ALTITUDE_LIMIT("0x1B310009", 2, 0, DeviceScope.DRONE),
        /** 限远（飞行器告警） */
        DISTANCE_LIMIT("0x1643010B", 1, 0, DeviceScope.DRONE),
        /** 严重低电量，请尽快返航或降落（飞行器告警） */
        BATTERY_LOW("0x1610000E", 2, 0, DeviceScope.DRONE);

        private final String code;
        private final int level;
        private final int imminent;
        private final DeviceScope scope;

        AlarmType(String code, int level, int imminent, DeviceScope scope) {
            this.code = code;
            this.level = level;
            this.imminent = imminent;
            this.scope = scope;
        }

        /** 从字符串解析枚举，无效时返回 null */
        public static AlarmType fromString(String name) {
            if (name == null) return null;
            try {
                return AlarmType.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** 设备归属：决定 device_type 字段使用 dock 还是 drone 的 model key */
    private enum DeviceScope {
        DOCK, DRONE
    }

    /**
     * 触发一次 HMS 事件上报。
     * <p>将所选异常类型构造为全量告警列表，发送到 thing/product/{gateway_sn}/events。</p>
     *
     * @param types 异常类型名称列表（与 {@link AlarmType} 枚举常量名一致，大小写不敏感）
     * @return 触发结果：成功时 success=true；失败时 success=false 且包含 code/message
     */
    public TriggerResult trigger(List<String> types) {
        // 参数校验：异常类型列表不能为空
        if (types == null || types.isEmpty()) {
            return TriggerResult.fail("INVALID_TYPES", "未选择任何异常类型");
        }

        // 状态校验：MQTT 未连接时无法上报
        if (!mqtt.isConnected()) {
            return TriggerResult.fail("MQTT_NOT_CONNECTED", "MQTT 未连接，无法上报 HMS");
        }

        // 解析并校验异常类型，忽略无效项
        List<AlarmType> alarms = types.stream()
                .map(AlarmType::fromString)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (alarms.isEmpty()) {
            return TriggerResult.fail("INVALID_TYPES", "异常类型均无效");
        }

        publishHmsEvent(alarms);
        log.info("HMS 事件已上报: count={}, types={}", alarms.size(),
                alarms.stream().map(Enum::name).toList());
        return TriggerResult.ok(alarms.size());
    }

    /**
     * 构造并发送 HMS 事件报文。
     * <p>报文格式遵循 DJI Cloud API 协议：
     * {@code {bid, tid, timestamp, method:"hms", data:{list:[{code,level,module,in_the_sky,device_type,imminent,args}]}}}</p>
     */
    private void publishHmsEvent(List<AlarmType> alarms) {
        List<Map<String, Object>> list = alarms.stream()
                .map(this::buildAlarmItem)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("need_reply", 0);  // DJI events 信封必填：0=不需要答复（HMS 为单向通知）
        envelope.put("gateway", runtimeConfig.getDockSn());  // DJI events 信封必填：网关设备 SN
        envelope.put("method", "hms");
        envelope.put("data", data);

        String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getDockSn());
        mqtt.publishJson(topic, envelope);
    }

    /**
     * 构造单个告警项。
     * <p>飞行器告警的 in_the_sky 字段根据当前无人机 mode_code 自动推断：
     * mode_code ∈ {4,5,9,10,13} 视为在空中，否则在地面。该字段影响 fpv_tip 文案 Key 后缀。</p>
     */
    private Map<String, Object> buildAlarmItem(AlarmType alarm) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("component_index", 0);
        args.put("sensor_index", 0);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", alarm.code);
        item.put("level", alarm.level);
        item.put("module", MODULE_HMS);
        // in_the_sky：机场告警固定 0；飞行器告警根据当前 mode_code 推断
        item.put("in_the_sky", alarm.scope == DeviceScope.DRONE ? inferInSky() : 0);
        item.put("device_type", resolveDeviceType(alarm.scope));
        item.put("imminent", alarm.imminent);
        item.put("args", args);
        return item;
    }

    /** 根据无人机当前 mode_code 推断 in_the_sky 字段值 */
    private int inferInSky() {
        return DRONE_IN_SKY_MODES.contains(state.getDroneModeCode()) ? 1 : 0;
    }

    /** 根据设备归属解析 device_type 字段（{domain-type-subtype} 格式，从 RuntimeConfig 读取避免硬编码） */
    private String resolveDeviceType(DeviceScope scope) {
        return scope == DeviceScope.DOCK
                ? runtimeConfig.getDockType().modelKey()
                : runtimeConfig.getDroneType().modelKey();
    }

    /**
     * 触发结果。
     * <p>遵循"业务逻辑返回明确拒绝原因而非抛异常"的约定。</p>
     */
    public record TriggerResult(boolean success, String code, String message, int count) {
        public static TriggerResult ok(int count) {
            return new TriggerResult(true, "OK", "HMS 上报成功", count);
        }

        public static TriggerResult fail(String code, String message) {
            return new TriggerResult(false, code, message, 0);
        }
    }
}
