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

import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.dji.cloudapi.sdk.protocol.topic.TopicChannel;
import ltd.cdmi.dji.cloudapi.sdk.protocol.topic.TopicTemplate;

/**
 * Pilot 上云（RC 作为网关）的 MQTT Topic 模式。
 * <p>实现 {@link TopicSchema} 接口，提供 Pilot 上云的 Topic 通道。</p>
 * <p>差异点（status/status_reply）按遥控器型号区分：
 * <ul>
 *   <li>DJI RC Plus / DJI RC Pro 行业版：sys/product/{sn}/status（与机场上云一致，取自 {@link TopicTemplate#STATUS}）</li>
 *   <li>DJI RC Plus 2 行业版：thing/product/{sn}/status（新一代遥控器协议变更，使用 thing/product 前缀）</li>
 * </ul>
 * <p>核实依据：用户提供的 Pilot 上云设备管理（update_topo）属性列表。
 * DJI RC Plus 2 行业版的 Topic 为 thing/product/{gateway_sn}/status，
 * 其他 Pilot 机型为 sys/product/{gateway_sn}/status。</p>
 * <p>其他通道（osd/state/services/...）与机场上云一致，使用 TopicSchema 默认实现。</p>
 */
public class PilotTopicSchema implements TopicSchema {

    /** thing/product 前缀的 status 模板（DJI RC Plus 2 行业版使用） */
    private static final String THING_PRODUCT_STATUS = TopicTemplate.thingProduct(TopicChannel.STATUS.suffix());
    /** thing/product 前缀的 status_reply 模板（DJI RC Plus 2 行业版使用，待真机验证） */
    private static final String THING_PRODUCT_STATUS_REPLY = TopicTemplate.thingProduct(TopicChannel.STATUS_REPLY.suffix());

    private final boolean rcPlus2;

    /**
     * 根据遥控器类型构造 Pilot 上云 Topic 模式。
     * @param controllerType 遥控器类型
     */
    public PilotTopicSchema(RcModel controllerType) {
        this.rcPlus2 = (controllerType == RcModel.RC_PLUS_2);
    }

    @Override
    public String status() {
        // DJI RC Plus 2 行业版用 thing/product/{sn}/status，其他机型用 sys/product/{sn}/status
        return rcPlus2 ? THING_PRODUCT_STATUS : TopicTemplate.STATUS;
    }

    @Override
    public String statusReply() {
        // DJI RC Plus 2 行业版的 status_reply Topic 待真机验证，暂与 status 保持一致
        return rcPlus2 ? THING_PRODUCT_STATUS_REPLY : TopicTemplate.STATUS_REPLY;
    }
}
