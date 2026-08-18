// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.

package ltd.cdmi.hivemind.simulator.http;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link HivemindHttpClient} 的 buildUrl 多值参数支持测试。
 * <p>通过反射调用私有方法 buildUrl，验证单值和多值查询参数的 URL 拼接。
 */
@DisplayName("HivemindHttpClient - 多值查询参数支持")
class HivemindHttpClientUrlTest {

    private HivemindHttpClient client;

    @BeforeEach
    void setUp() {
        RuntimeConfig runtimeConfig = mock(RuntimeConfig.class);
        when(runtimeConfig.getHivemindHttpTimeout()).thenReturn(5000);
        client = new HivemindHttpClient(runtimeConfig, new ObjectMapper());
    }

    @Test
    @DisplayName("单值查询参数：?key=value")
    void buildUrlWithSingleValueParams() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("group_id", "grp1");
        params.put("is_distributed", true);

        String url = invokeBuildUrl("/path", params);
        assertEquals("/path?group_id=grp1&is_distributed=true", url);
    }

    @Test
    @DisplayName("多值查询参数（数组）：?key=v1&key=v2")
    void buildUrlWithMultiValueParams() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", List.of("航线1", "航线2"));

        String url = invokeBuildUrl("/path", params);
        assertEquals("/path?name=航线1&name=航线2", url);
    }

    @Test
    @DisplayName("混合单值和多值查询参数")
    void buildUrlWithMixedParams() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("page", 1);
        params.put("name", List.of("a", "b", "c"));

        String url = invokeBuildUrl("/path", params);
        assertEquals("/path?page=1&name=a&name=b&name=c", url);
    }

    @Test
    @DisplayName("null 查询参数不添加 ?")
    void buildUrlWithNullParams() throws Exception {
        String url = invokeBuildUrl("/path", null);
        assertEquals("/path", url);
    }

    @Test
    @DisplayName("空 Map 查询参数不添加 ?")
    void buildUrlWithEmptyParams() throws Exception {
        String url = invokeBuildUrl("/path", Map.of());
        assertEquals("/path", url);
    }

    /** 通过反射调用私有方法 buildUrl */
    private String invokeBuildUrl(String path, Map<String, ?> params) throws Exception {
        var method = HivemindHttpClient.class.getDeclaredMethod("buildUrl", String.class, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(client, path, params);
    }
}
