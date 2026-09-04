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
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProtocolValidator 单元测试。
 * <p>覆盖 TC-DIAG-007~012 中 ProtocolValidator 可测的部分：
 * <ul>
 *   <li>TC-DIAG-007：JSON 格式错误检测（P-5）— classifyException</li>
 *   <li>TC-DIAG-008：必填字段缺失检测（P-6）— validateFields / validateDrcFields</li>
 *   <li>TC-DIAG-009：字段类型错误检测（P-7）— validateFields / validateDrcFields</li>
 *   <li>TC-DIAG-010：Dock 能力不匹配检测（P-8）— 在 WaylineTaskSimulator 层测试，此处不覆盖</li>
 *   <li>TC-DIAG-011：未覆盖指令检测（S-2）— 在 ServiceCommandHandler 层测试，此处不覆盖</li>
 *   <li>TC-DIAG-012：解析异常检测（S-3）— classifyException</li>
 * </ul>
 */
class ProtocolValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== TC-DIAG-007：JSON 格式错误检测（P-5） ====================

    @Test
    @DisplayName("TC-DIAG-007: JsonParseException 归类为 P-5")
    void jsonParseExceptionClassifiedAsP5() {
        JsonParseException e = new JsonParseException(null, "Unexpected character");
        DiagnosticCode code = ProtocolValidator.classifyException(e);
        assertEquals(DiagnosticCode.PLATFORM_JSON_FORMAT_ERROR, code, "JsonParseException 应归类为 P-5");
    }

    @Test
    @DisplayName("TC-DIAG-007: JsonProcessingException（非 Mapping）归类为 P-5")
    void jsonProcessingExceptionClassifiedAsP5() {
        com.fasterxml.jackson.core.JsonProcessingException e =
                new com.fasterxml.jackson.core.JsonProcessingException("Generic JSON error", (com.fasterxml.jackson.core.JsonLocation) null) {};
        DiagnosticCode code = ProtocolValidator.classifyException(e);
        assertEquals(DiagnosticCode.PLATFORM_JSON_FORMAT_ERROR, code, "JsonProcessingException 应归类为 P-5");
    }

    // ==================== TC-DIAG-008：必填字段缺失检测（P-6） ====================

    @Test
    @DisplayName("TC-DIAG-008: services 缺 tid 返回 P-6")
    void servicesMissingTidReturnsP6() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"bid":"b1","method":"cover_open","data":{}}
                """);
        DiagnosticCode code = ProtocolValidator.validateFields(node);
        assertEquals(DiagnosticCode.PLATFORM_FIELD_MISSING, code, "缺 tid 应返回 P-6");
    }

    @Test
    @DisplayName("TC-DIAG-008: services 缺 method 返回 P-6")
    void servicesMissingMethodReturnsP6() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"tid":"t1","bid":"b1","data":{}}
                """);
        DiagnosticCode code = ProtocolValidator.validateFields(node);
        assertEquals(DiagnosticCode.PLATFORM_FIELD_MISSING, code, "缺 method 应返回 P-6");
    }

    @Test
    @DisplayName("TC-DIAG-008: DRC 缺 seq 返回 P-6")
    void drcMissingSeqReturnsP6() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"method":"drc_force_landing","data":{}}
                """);
        DiagnosticCode code = ProtocolValidator.validateDrcFields(node);
        assertEquals(DiagnosticCode.PLATFORM_FIELD_MISSING, code, "DRC 缺 seq 应返回 P-6");
    }

    @Test
    @DisplayName("TC-DIAG-008: property/set 无 method 字段（协议合法）校验通过")
    void propertySetWithoutMethodPasses() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"tid":"t1","bid":"b1","timestamp":1750000000000,"data":{"silent_mode":1}}
                """);
        DiagnosticCode code = ProtocolValidator.validatePropertySetFields(node);
        assertNull(code, "property/set 无 method 是协议合法格式，应校验通过");
    }

    @Test
    @DisplayName("TC-DIAG-008: property/set 缺 tid 返回 P-6")
    void propertySetMissingTidReturnsP6() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"bid":"b1","timestamp":1750000000000,"data":{"silent_mode":1}}
                """);
        DiagnosticCode code = ProtocolValidator.validatePropertySetFields(node);
        assertEquals(DiagnosticCode.PLATFORM_FIELD_MISSING, code, "property/set 缺 tid 应返回 P-6");
    }

    @Test
    @DisplayName("TC-DIAG-008: 字段齐全时返回 null（校验通过）")
    void allFieldsPresentReturnsNull() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"tid":"t1","bid":"b1","method":"cover_open","data":{}}
                """);
        DiagnosticCode code = ProtocolValidator.validateFields(node);
        assertNull(code, "字段齐全应返回 null");
    }

    // ==================== TC-DIAG-009：字段类型错误检测（P-7） ====================

    @Test
    @DisplayName("TC-DIAG-009: services method 为数字返回 P-7")
    void servicesMethodNotStringReturnsP7() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"tid":"t1","bid":"b1","method":123,"data":{}}
                """);
        DiagnosticCode code = ProtocolValidator.validateFields(node);
        assertEquals(DiagnosticCode.PLATFORM_FIELD_TYPE_ERROR, code, "method 非字符串应返回 P-7");
    }

    @Test
    @DisplayName("TC-DIAG-009: DRC method 为数字返回 P-7")
    void drcMethodNotStringReturnsP7() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"method":123,"data":{},"seq":1}
                """);
        DiagnosticCode code = ProtocolValidator.validateDrcFields(node);
        assertEquals(DiagnosticCode.PLATFORM_FIELD_TYPE_ERROR, code, "DRC method 非字符串应返回 P-7");
    }

    @Test
    @DisplayName("TC-DIAG-009: DRC seq 为字符串返回 P-7")
    void drcSeqNotIntReturnsP7() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"method":"drc_force_landing","data":{},"seq":"abc"}
                """);
        DiagnosticCode code = ProtocolValidator.validateDrcFields(node);
        assertEquals(DiagnosticCode.PLATFORM_FIELD_TYPE_ERROR, code, "DRC seq 非整数应返回 P-7");
    }

    @Test
    @DisplayName("TC-DIAG-009: JsonMappingException 归类为 P-7")
    void jsonMappingExceptionClassifiedAsP7() {
        JsonMappingException e = new JsonMappingException(null, "Cannot deserialize");
        DiagnosticCode code = ProtocolValidator.classifyException(e);
        assertEquals(DiagnosticCode.PLATFORM_FIELD_TYPE_ERROR, code, "JsonMappingException 应归类为 P-7");
    }

    // ==================== TC-DIAG-012：解析异常检测（S-3） ====================

    @Test
    @DisplayName("TC-DIAG-012: NullPointerException 归类为 S-3")
    void npeClassifiedAsS3() {
        NullPointerException e = new NullPointerException("field is null");
        DiagnosticCode code = ProtocolValidator.classifyException(e);
        assertEquals(DiagnosticCode.SIMULATOR_PARSE_BUG, code, "NPE 应归类为 S-3");
    }

    @Test
    @DisplayName("TC-DIAG-012: ClassCastException 归类为 S-3")
    void classCastExceptionClassifiedAsS3() {
        ClassCastException e = new ClassCastException("Cannot cast to String");
        DiagnosticCode code = ProtocolValidator.classifyException(e);
        assertEquals(DiagnosticCode.SIMULATOR_PARSE_BUG, code, "ClassCastException 应归类为 S-3");
    }

    @Test
    @DisplayName("TC-DIAG-012: 其他 RuntimeException 归类为 S-3")
    void otherRuntimeExceptionClassifiedAsS3() {
        IllegalStateException e = new IllegalStateException("Unexpected state");
        DiagnosticCode code = ProtocolValidator.classifyException(e);
        assertEquals(DiagnosticCode.SIMULATOR_PARSE_BUG, code, "其他运行时异常应归类为 S-3");
    }

    // ==================== 辅助方法测试 ====================

    @Test
    @DisplayName("logPrefix 格式正确")
    void logPrefixFormatCorrect() {
        String prefix = ProtocolValidator.logPrefix(DiagnosticCode.PLATFORM_JSON_FORMAT_ERROR);
        assertEquals("[P-5] JSON 格式错误", prefix, "logPrefix 格式应为 [code] description");
    }
}
