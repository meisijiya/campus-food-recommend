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
        // F-4 review fix:验证任意 isAuthenticated() 的受保护端点(而非 /actuator/info — 已改 authenticated)。
        // 用 /api/merchant/{id} 触发 @PreAuthorize("isAuthenticated()") + SecurityConfig.anyRequest().authenticated()
        mockMvc.perform(get("/api/merchant/M-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
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