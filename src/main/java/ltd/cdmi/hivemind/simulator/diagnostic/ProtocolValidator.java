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

package ltd.cdmi.hivemind.simulator.diagnostic;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 协议校验引擎：校验 MQTT 消息的协议合规性，区分平台错误与模拟器问题。
 * <p>两类方法：
 * <ul>
 *   <li>{@link #validateFields}：主动校验已解析消息的必填字段（P-6）和字段类型（P-7）</li>
 *   <li>{@link #classifyException}：被动归类 catch 块捕获的异常（P-5 或 S-3）</li>
 * </ul>
 * <p>设计原则：无状态工具类（静态方法），不改变现有代码流程，只在日志中输出诊断码。
 * <p>真相源：TDD-SPEC §2.14 TC-DIAG-007~012、设计文档 §8 错误码体系。</p>
 */
public final class ProtocolValidator {

    private ProtocolValidator() {}

    /**
     * 主动校验已解析消息的必填字段和字段类型。
     * <p>校验顺序：必填字段（P-6）→ 字段类型（P-7），快速失败返回第一个错误。
     * <p>适用于 services / property_set 等包含 tid/bid/method 的下行消息。
     * @param node 已解析的 JSON 消息（非 null）
     * @return null=校验通过；DiagnosticCode=第一个错误
     */
    public static DiagnosticCode validateFields(JsonNode node) {
        // P-6：必填字段校验（tid/bid/method 是 DJI Cloud API 所有下行指令的必填字段）
        List<String> missing = new ArrayList<>();
        if (!node.has("tid")) missing.add("tid");
        if (!node.has("bid")) missing.add("bid");
        if (!node.has("method")) missing.add("method");
        if (!missing.isEmpty()) {
            return DiagnosticCode.PLATFORM_FIELD_MISSING;
        }

        // P-7：字段类型校验（method 必须是字符串）
        JsonNode methodNode = node.get("method");
        if (!methodNode.isTextual()) {
            return DiagnosticCode.PLATFORM_FIELD_TYPE_ERROR;
        }

        return null;
    }

    /**
     * 主动校验 DRC 下行消息（drc/down）的必填字段和字段类型。
     * <p>DRC 消息格式与 services 不同：{@code {method, data, seq}}，无 tid/bid。
     * <p>校验顺序：必填字段（P-6）→ 字段类型（P-7），快速失败返回第一个错误。
     * @param node 已解析的 JSON 消息（非 null）
     * @return null=校验通过；DiagnosticCode=第一个错误
     */
    public static DiagnosticCode validateDrcFields(JsonNode node) {
        // P-6：必填字段校验（method/seq 是 DRC 下行指令的必填字段）
        List<String> missing = new ArrayList<>();
        if (!node.has("method")) missing.add("method");
        if (!node.has("seq")) missing.add("seq");
        if (!missing.isEmpty()) {
            return DiagnosticCode.PLATFORM_FIELD_MISSING;
        }

        // P-7：字段类型校验（method 必须是字符串，seq 必须是整数）
        JsonNode methodNode = node.get("method");
        if (!methodNode.isTextual()) {
            return DiagnosticCode.PLATFORM_FIELD_TYPE_ERROR;
        }
        JsonNode seqNode = node.get("seq");
        if (!seqNode.isInt()) {
            return DiagnosticCode.PLATFORM_FIELD_TYPE_ERROR;
        }

        return null;
    }

    /**
     * 被动归类 catch 块捕获的异常为诊断码。
     * <p>归类规则：
     * <ul>
     *   <li>{@link JsonParseException} → P-5（JSON 格式错误，平台责任）</li>
     *   <li>{@link JsonMappingException} → P-7（字段映射错误，平台责任）</li>
     *   <li>{@link NullPointerException} / {@link ClassCastException} → S-3（模拟器 Bug）</li>
     *   <li>其他 → S-3（模拟器内部异常）</li>
     * </ul>
     * @param e catch 块捕获的异常
     * @return 诊断码（永不为 null）
     */
    public static DiagnosticCode classifyException(Exception e) {
        if (e instanceof JsonParseException) {
            return DiagnosticCode.PLATFORM_JSON_FORMAT_ERROR;
        }
        if (e instanceof JsonMappingException) {
            return DiagnosticCode.PLATFORM_FIELD_TYPE_ERROR;
        }
        if (e instanceof JsonProcessingException) {
            return DiagnosticCode.PLATFORM_JSON_FORMAT_ERROR;
        }
        // NPE/ClassCastException 等运行时异常归为模拟器 Bug
        return DiagnosticCode.SIMULATOR_PARSE_BUG;
    }

    /**
     * 构建诊断日志前缀，如 "[P-5] JSON 格式错误"。
     * @param code 诊断码
     * @return 日志前缀字符串
     */
    public static String logPrefix(DiagnosticCode code) {
        return "[" + code.code() + "] " + code.description();
    }
}
