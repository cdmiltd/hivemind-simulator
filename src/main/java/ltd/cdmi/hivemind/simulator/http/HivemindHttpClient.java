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

package ltd.cdmi.hivemind.simulator.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.Map;

/**
 * hivemind HTTP API 通用客户端。
 * <p>封装 RestTemplate，提供 get/post/put/delete 方法，自动拼接 base URL 和添加认证头。
 * <p>base-url 和 timeout 从 {@link RuntimeConfig} 读取，支持运行时覆盖。
 * <p>所有方法返回 {@link HivemindResponse}，业务逻辑返回明确结果而非抛异常。</p>
 * <p>查询参数支持单值（String）和多值（Collection），适配 DJI API 的数组查询参数需求。</p>
 */
@Component
public class HivemindHttpClient {

    private static final Logger log = LoggerFactory.getLogger(HivemindHttpClient.class);

    private final RuntimeConfig runtimeConfig;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public HivemindHttpClient(RuntimeConfig runtimeConfig, ObjectMapper objectMapper) {
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(runtimeConfig.getHivemindHttpTimeout());
        factory.setReadTimeout(runtimeConfig.getHivemindHttpTimeout());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * GET 请求。
     *
     * @param queryParams 查询参数，值可以是 String（单值）或 Collection（多值，如 DJI API 的数组参数）
     */
    public HivemindResponse get(String path, Map<String, ?> queryParams) {
        String url = buildUrl(path, queryParams);
        return execute(HttpMethod.GET, url, null);
    }

    /** POST 请求 */
    public HivemindResponse post(String path, Object body) {
        String url = buildUrl(path, null);
        return execute(HttpMethod.POST, url, body);
    }

    /** PUT 请求 */
    public HivemindResponse put(String path, Object body) {
        String url = buildUrl(path, null);
        return execute(HttpMethod.PUT, url, body);
    }

    /** DELETE 请求（无查询参数） */
    public HivemindResponse delete(String path) {
        String url = buildUrl(path, null);
        return execute(HttpMethod.DELETE, url, null);
    }

    /**
     * DELETE 请求（带查询参数）。
     *
     * @param queryParams 查询参数，值可以是 String（单值）或 Collection（多值）
     */
    public HivemindResponse delete(String path, Map<String, ?> queryParams) {
        String url = buildUrl(path, queryParams);
        return execute(HttpMethod.DELETE, url, null);
    }

    private HivemindResponse execute(HttpMethod method, String url, Object body) {
        String baseUrl = runtimeConfig.getHivemindHttpBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("hivemind HTTP base-url 未配置，跳过请求: {} {}", method, url);
            return HivemindResponse.notConfigured();
        }

        try {
            HttpHeaders headers = buildHeaders();
            HttpEntity<String> entity = new HttpEntity<>(
                    body != null ? objectMapper.writeValueAsString(body) : null, headers);
            String fullUrl = baseUrl + url;
            log.debug("hivemind HTTP 请求: {} {}", method, fullUrl);

            String responseBody = restTemplate.exchange(fullUrl, method, entity, String.class).getBody();
            JsonNode json = objectMapper.readTree(responseBody);
            int code = json.path("code").asInt(-1);
            String message = json.path("message").asText("");
            JsonNode data = json.path("data");
            log.info("hivemind HTTP 响应: {} {} code={} message={}", method, url, code, message);
            return new HivemindResponse(true, code, message, data);
        } catch (Exception e) {
            log.error("hivemind HTTP 请求失败: {} {} - {}", method, url, e.getMessage());
            return new HivemindResponse(false, -1, e.getMessage(), null);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // x-auth-token: DJI Pilot HTTP API 要求的访问令牌
        // 从 RuntimeConfig 读取（yml 默认或前端覆盖），为空时使用空串
        String token = runtimeConfig.getHivemindHttpToken();
        headers.set("x-auth-token", token != null ? token : "");
        return headers;
    }

    /**
     * 构建带查询参数的 URL。
     * <p>支持单值（String）和多值（Collection）参数：
     * <ul>
     *   <li>单值：{@code ?key=value}</li>
     *   <li>多值：{@code ?key=v1&key=v2}（DJI API 的数组查询参数标准格式）</li>
     * </ul>
     */
    private String buildUrl(String path, Map<String, ?> queryParams) {
        StringBuilder url = new StringBuilder(path);
        if (queryParams != null && !queryParams.isEmpty()) {
            url.append("?");
            queryParams.forEach((k, v) -> {
                if (v instanceof Collection<?> col) {
                    col.forEach(item -> url.append(k).append("=").append(item).append("&"));
                } else {
                    url.append(k).append("=").append(v).append("&");
                }
            });
            url.deleteCharAt(url.length() - 1);
        }
        return url.toString();
    }

    /** hivemind HTTP API 统一响应 */
    public record HivemindResponse(boolean success, int code, String message, JsonNode data) {
        public static HivemindResponse notConfigured() {
            return new HivemindResponse(false, -1, "hivemind HTTP base-url 未配置", null);
        }
    }
}
