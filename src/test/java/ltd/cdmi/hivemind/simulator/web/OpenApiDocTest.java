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

package ltd.cdmi.hivemind.simulator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * springdoc-openapi 接口文档端点测试（TDD-SPEC TC-API-DOC-001~003）。
 * <p>验证 OpenAPI JSON、Swagger UI 可访问，接口按 Controller 分组且文档元数据正确。
 * 应用启动不依赖 MQTT 连接（红线：Spring 启动时不自动连接），可安全启动完整上下文。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class OpenApiDocTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 应用版本（application.yml 由 Maven 资源过滤注入 project.version），动态读取避免硬编码 */
    @org.springframework.beans.factory.annotation.Value("${application.version}")
    private String appVersion;

    /** TC-API-DOC-001：OpenAPI JSON 端点可用，包含核心端点 */
    @Test
    void apiDocsShouldContainCoreEndpoints() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("application/json", result.getResponse().getContentType().split(";")[0].trim());

        // 中文内容需按字节流解析，getContentAsString() 默认 ISO-8859-1 会乱码
        JsonNode openApi = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertEquals("3.0.1", openApi.path("openapi").asText(), "openapi 版本应为 3.0.1");
        assertTrue(openApi.path("paths").has("/api/device-info"), "应包含模拟器端点 /api/device-info");
        assertTrue(openApi.path("paths").has("/api/monitor/status"), "应包含监控器端点 /api/monitor/status");
    }

    /** TC-API-DOC-002：Swagger UI 页面可用，/swagger-ui.html 重定向 */
    @Test
    void swaggerUiShouldBeAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
    }

    /** TC-API-DOC-003：接口分组与文档元数据（title/version/tags） */
    @Test
    void apiDocsShouldHaveCorrectMetadataAndTags() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        // 中文内容需按字节流解析，getContentAsString() 默认 ISO-8859-1 会乱码
        JsonNode openApi = objectMapper.readTree(result.getResponse().getContentAsByteArray());

        // 文档元数据
        assertEquals("DJI Dock 模拟器 REST API", openApi.path("info").path("title").asText());
        assertEquals(appVersion, openApi.path("info").path("version").asText(), "版本号应与 pom.xml 一致");

        // 接口分组：模拟器控制 / 监控器
        JsonNode tags = openApi.path("tags");
        assertTrue(tags.isArray() && tags.size() >= 2, "至少应有两个分组 tag");
        boolean hasSimulatorTag = false;
        boolean hasMonitorTag = false;
        for (JsonNode tag : tags) {
            if ("模拟器控制".equals(tag.path("name").asText())) {
                hasSimulatorTag = true;
            }
            if ("监控器".equals(tag.path("name").asText())) {
                hasMonitorTag = true;
            }
        }
        assertTrue(hasSimulatorTag, "应有「模拟器控制」分组");
        assertTrue(hasMonitorTag, "应有「监控器」分组");

        // 端点归属分组
        JsonNode deviceInfoOp = openApi.path("paths").path("/api/device-info").path("get");
        assertNotNull(deviceInfoOp, "/api/device-info GET 定义应存在");
        assertEquals("模拟器控制", deviceInfoOp.path("tags").path(0).asText());
    }
}
