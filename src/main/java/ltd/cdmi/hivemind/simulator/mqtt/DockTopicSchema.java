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

import ltd.cdmi.dji.cloudapi.sdk.protocol.topic.TopicTemplate;
import org.springframework.stereotype.Component;

/**
 * 机场上云（机场作为网关）的 MQTT Topic 模式。
 * <p>实现 {@link TopicSchema} 接口，提供机场上云的 Topic 通道。</p>
 * <p>机场上云的 status/status_reply 使用 sys/product/{sn}/...（取自 SDK {@link TopicTemplate#STATUS}），
 * 其他通道（osd/state/services/...）使用 thing/product/{sn}/...（TopicSchema 默认实现）。</p>
 * <p>核实依据：DJI Cloud API 机场上云设备管理（update_topo）属性列表。
 * 机场作为网关设备，Topic 为 sys/product/{gateway_sn}/status。</p>
 */
@Component
public class DockTopicSchema implements TopicSchema {

    @Override
    public String status() {
        return TopicTemplate.STATUS;
    }

    @Override
    public String statusReply() {
        return TopicTemplate.STATUS_REPLY;
    }
}
