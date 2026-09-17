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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi 文档元数据配置（TDD-SPEC TC-API-DOC-003）。
 * <p>Swagger UI 入口 {@code /swagger-ui.html}，OpenAPI JSON 入口 {@code /v3/api-docs}。
 * 接口按 Controller 分组：模拟器控制（{@link SimulatorController}）、监控器（{@link MonitorController}）。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI dockSimulatorOpenApi(
            @Value("${application.version}") String version) {
        return new OpenAPI().info(new Info()
                .title("DJI Dock 模拟器 REST API")
                .description("DJI Dock 机场模拟器/监控器的 REST 接口文档。"
                        + "模拟器控制端点（/api）驱动设备注册、上线、OSD/State 上报、航线任务、直播、媒体上传等模拟流程；"
                        + "监控器端点（/api/monitor）提供 MQTT 消息订阅与设备状态查询，用于排查平台侧协议交互问题。")
                .version(version));
    }
}
