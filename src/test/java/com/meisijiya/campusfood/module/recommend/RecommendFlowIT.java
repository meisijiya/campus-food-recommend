package com.meisijiya.campusfood.module.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meisijiya.campusfood.module.recommend.schema.JsonSchemaValidator;

/**
 * 推荐流程端到端集成测试(F-3;覆盖 ticket acceptance #11:dev profile 端到端 → schema 合规)。
 *
 * <p>策略:用 {@code @MockitoBean} 替换 {@link StringRedisTemplate},配合内存 map 模拟 Redis
 * (与 SessionControllerTest 一致);{@code recommendChatClient} 链路注入真实
 * {@code MockChatModel}(F-1/F-2 已实现,固定返回合规 JSON)+ F-3 的两个 Advisor。
 *
 * <p>断言:
 * <ul>
 *   <li>POST /api/recommend 200</li>
 *   <li>响应 body.data.content 能被 {@link JsonSchemaValidator#isValid} 校验通过</li>
 * </ul>
 *
 * @author meisijiya
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RecommendFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JsonSchemaValidator validator;

    @MockitoBean
    private StringRedisTemplate redis;

    private Map<String, String> store;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(ValueOperations.class);

        Answer<Void> putAnswer = inv -> {
            store.put((String) inv.getArgument(0), (String) inv.getArgument(1));
            return null;
        };
        org.mockito.Mockito.doAnswer(putAnswer).when(valueOps)
                .set(org.mockito.ArgumentMatchers.any(String.class),
                     org.mockito.ArgumentMatchers.any(String.class));
        org.mockito.Mockito.doAnswer(putAnswer).when(valueOps)
                .set(org.mockito.ArgumentMatchers.any(String.class),
                     org.mockito.ArgumentMatchers.any(String.class),
                     org.mockito.ArgumentMatchers.anyLong(),
                     org.mockito.ArgumentMatchers.any());

        org.mockito.Mockito.when(valueOps.get(org.mockito.ArgumentMatchers.any(String.class)))
                .thenAnswer(inv -> store.get((String) inv.getArgument(0)));

        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.when(redis.delete((Collection<String>) org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    Collection<String> keys = inv.getArgument(0);
                    long n = 0;
                    for (String k : new java.util.ArrayList<>(keys)) {
                        if (store.remove(k) != null) n++;
                    }
                    return n;
                });
    }

    @Test
    @DisplayName("POST /api/recommend 未鉴权 → 401")
    void withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/recommend").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/recommend 鉴权后 → 200 + 内容符合 RecommendationSchema")
    void withAuth_returns200AndSchemaCompliant() throws Exception {
        String responseBody = mockMvc.perform(post("/api/recommend")
                        .with(csrf())
                        .with(user("demo-user").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(responseBody);
        String content = root.path("data").path("content").asText();

        // MockChatModel 返回的是 F-1 阶段约定的固定 JSON,本身 schema 合规;
        // 再走 JsonSchemaValidator 双重断言。
        assertThat(content).isNotBlank();
        assertThat(validator.isValid(content))
                .as("recommended content must satisfy RecommendationSchema: %s", content)
                .isTrue();
    }
}