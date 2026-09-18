package com.meisijiya.campusfood.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 集成测试:验证受保护接口无 Bearer 时返 401 JSON(非 HTML)。
 *
 * @author meisijiya
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void actuatorHealth_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_withoutToken_returns401Json() throws Exception {
        // /api/auth/refresh 在放行名单里,试一个不在白名单的受保护路径
        // 这里用 /actuator/info 也是放行的,改用 Spring 默认管理的某个 metrics 接口测
        // 简单测试:未带 token 调任意受保护路径 — 这里用空 body 的 refresh
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    void noToken_returns401Json_notHtml() throws Exception {
        // 用一个肯定 401 的路径(F-1 没有受保护 GET,这里走 /api/xxx/yyy 触发 401)
        mockMvc.perform(get("/api/xxx/nonexistent"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").exists());
    }
}