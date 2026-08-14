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

package ltd.cdmi.hivemind.simulator.mqtt;

/**
 * DJI Cloud API MQTT Topic 模式接口。
 * <p>Pilot 上云（RC 作为网关）和机场上云（机场作为网关）的 Topic 结构分离：
 * 即使两者 100% 一样，也应从共同结构继承扩展，各自使用各自的 Topic 通道。</p>
 * <p>差异点：{@link #status()} 和 {@link #statusReply()} 由子类实现。
 * 机场上云用 sys/product/{sn}/status，Pilot 上云其他机型用 sys/product/{sn}/status，
 * DJI RC Plus 2 行业版用 thing/product/{sn}/status。</p>
 * <p>共同点：其他通道（osd/state/services/...）统一为 thing/product/{sn}/...，
 * 由默认方法提供。</p>
 * <p>topic 命名规则见 DJI Cloud API 基础概念文档。</p>
 */
public interface TopicSchema {

    // ==================== 差异点：由子类实现 ====================

    /** 设备拓扑上行：设备→云，用于上线/下线通知 */
    String status();

    /** 设备拓扑回复：云→设备 */
    String statusReply();

    // ==================== 共同点：默认实现（thing/product/{sn}/...） ====================

    /** OSD 遥测数据上行 */
    default String osd() { return "thing/product/%s/osd"; }

    /** DRC 上行通道（设备→云，DRC 模式下的实时状态推送） */
    default String drcUp() { return "thing/product/%s/drc/up"; }

    /** DRC 下行通道（云→设备，DRC 模式下的实时控制指令） */
    default String drcDown() { return "thing/product/%s/drc/down"; }

    /** 状态变化上行（state） */
    default String state() { return "thing/product/%s/state"; }

    /** 事件上行（events） */
    default String events() { return "thing/product/%s/events"; }

    /** 请求上行（requests，设备主动向云请求，如 config/storage_config_get） */
    default String requests() { return "thing/product/%s/requests"; }

    /** 服务调用下行（services） */
    default String services() { return "thing/product/%s/services"; }

    /** 属性设置下行（property/set） */
    default String propertySet() { return "thing/product/%s/property/set"; }

    /** 服务调用回复：设备→云，应答 services */
    default String servicesReply() { return "thing/product/%s/services_reply"; }

    /** 事件回复：云→设备，应答 events */
    default String eventsReply() { return "thing/product/%s/events_reply"; }

    /** 请求回复：云→设备，应答 requests */
    default String requestsReply() { return "thing/product/%s/requests_reply"; }

    /** 属性设置回复：设备→云，应答 property/set */
    default String propertySetReply() { return "thing/product/%s/property/set_reply"; }

    // ==================== 工具方法 ====================

    /**
     * 格式化 topic，替换 %s 为设备 SN。
     * @param template topic 模板，如 {@link #osd()}
     * @param sn 设备序列号
     * @return 完整 topic，如 thing/product/SIM-DOCK3-001/osd
     */
    default String topic(String template, String sn) {
        return String.format(template, sn);
    }
}
