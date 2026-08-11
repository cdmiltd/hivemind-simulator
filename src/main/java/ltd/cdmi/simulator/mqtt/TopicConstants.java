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

package ltd.cdmi.simulator.mqtt;

/**
 * DJI Cloud API MQTT Topic 模板常量（Dock3）。
 * <p>topic 命名规则见 DJI Cloud API 基础概念文档。</p>
 * <ul>
 *   <li>上行（设备→云）：sys/product/{sn}/status、thing/product/{sn}/osd 等</li>
 *   <li>下行（云→设备）：thing/product/{sn}/services、property/set 等</li>
 *   <li>双向 reply：thing/product/{sn}/services_reply 等</li>
 * </ul>
 */
public final class TopicConstants {

    private TopicConstants() {}

    // ==================== 设备拓扑上下行（上线/下线） ====================

    /** 设备拓扑上行：设备→云，用于上线/下线通知 */
    public static final String STATUS = "sys/product/%s/status";
    /** 设备拓扑回复：云→设备 */
    public static final String STATUS_REPLY = "sys/product/%s/status_reply";

    // ==================== 物模型上行（设备→云） ====================

    /** OSD 遥测数据上行 */
    public static final String OSD = "thing/product/%s/osd";
    /** DRC 上行通道（设备→云，DRC 模式下的实时状态推送） */
    public static final String DRC_UP = "thing/product/%s/drc/up";
    /** DRC 下行通道（云→设备，DRC 模式下的实时控制指令） */
    public static final String DRC_DOWN = "thing/product/%s/drc/down";
    /** 状态变化上行（state） */
    public static final String STATE = "thing/product/%s/state";
    /** 事件上行（events） */
    public static final String EVENTS = "thing/product/%s/events";
    /** 请求上行（requests，设备主动向云请求，如 config/storage_config_get） */
    public static final String REQUESTS = "thing/product/%s/requests";

    // ==================== 物模型下行（云→设备） ====================

    /** 服务调用下行（services） */
    public static final String SERVICES = "thing/product/%s/services";
    /** 属性设置下行（property/set） */
    public static final String PROPERTY_SET = "thing/product/%s/property/set";

    // ==================== 物模型回复上行（设备→云，应答云的下行） ====================

    /** 服务调用回复：设备→云，应答 services */
    public static final String SERVICES_REPLY = "thing/product/%s/services_reply";
    /** 事件回复：云→设备，应答 events */
    public static final String EVENTS_REPLY = "thing/product/%s/events_reply";
    /** 请求回复：云→设备，应答 requests */
    public static final String REQUESTS_REPLY = "thing/product/%s/requests_reply";
    /** 属性设置回复：设备→云，应答 property/set */
    public static final String PROPERTY_SET_REPLY = "thing/product/%s/property/set_reply";

    // ==================== 工具方法 ====================

    /**
     * 格式化 topic，替换 %s 为设备 SN。
     * @param template topic 模板，如 {@link #OSD}
     * @param sn 设备序列号
     * @return 完整 topic，如 thing/product/SIM-DOCK3-001/osd
     */
    public static String topic(String template, String sn) {
        return String.format(template, sn);
    }
}
