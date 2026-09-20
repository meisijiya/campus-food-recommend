package com.meisijiya.campusfood.controller.featureflag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.meisijiya.campusfood.config.JwtAuthenticationFilter;
import com.meisijiya.campusfood.module.auth.JwtService;
import com.meisijiya.campusfood.module.featureflag.FeatureFlagService;
import com.meisijiya.campusfood.module.featureflag.FlagConfig;
import com.meisijiya.campusfood.module.ratelimit.RateLimitDegradationMonitor;
import com.meisijiya.campusfood.module.ratelimit.RateLimitFilter;

/**
 * F-11 W2:FeatureFlagAdminController 单元测试 — 仅 web 层 + MockMvc。
 *
 * <h2>测试隔离策略</h2>
 * <p>与 {@code FeatureFlagControllerTest} 同款:
 * <ol>
 *   <li>排除 {@link SecurityAutoConfiguration};</li>
 *   <li>{@code @MockitoBean} mock 两个 servlet Filter 本体与它们的直接依赖;</li>
 *   <li>{@code @AutoConfigureMockMvc(addFilters = false)} 关闭 servlet filter chain。
 *       本 controller 上 {@code @PreAuthorize("hasRole('ADMIN')")} 由
 *       {@code SecurityConfig.@EnableMethodSecurity} 开启,WebMvcTest 不加载 SecurityConfig,
 *       所以 method-level Security 不会拦截 — 这与生产(filter chain + AOP 双拦截)略有差异,
 *       SecurityConfig 单独集成测试覆盖。</li>
 * </ol>
 *
 * <h2>为什么 controller 接 {@code JsonNode}</h2>
 * <p>直接接 {@code FlagConfig} 会让 Jackson 反序列化阶段抛 {@link IllegalArgumentException},
 * 被 Spring wrap 成 {@code HttpMessageNotReadableException} 走 {@code GlobalExceptionHandler.handleAny}
 * 兜底成 500;本测试断言 4 期望 400,所以 controller 改接 {@code JsonNode} 由内部
 * ObjectMapper 转 FlagConfig 并 catch {@code IAE} 转 {@code ApiException(BAD_REQUEST)}。
 *
 * @author meisijiya
 */
@WebMvcTest(controllers = FeatureFlagAdminController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class FeatureFlagAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeatureFlagService featureFlagService;

    // 占位 mock,让 Spring 上下文能干净启动;本测试不走真实 Redis / JwtService / RateLimiter。
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private RateLimitFilter rateLimitFilter;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private RateLimitDegradationMonitor rateLimitDegradationMonitor;

    @Test
    @DisplayName("GET /admin/feature-flag → 200,响应体是 flagKey Set")
    void list_returnsFlagKeySet() throws Exception {
        Set<String> flags = new LinkedHashSet<>();
        flags.add("like-cache-bypass");
        flags.add("merchant-detail-new");
        flags.add("recommend-v2");
        when(featureFlagService.listFlags()).thenReturn(flags);

        mockMvc.perform(get("/admin/feature-flag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@ == 'recommend-v2')]").exists())
                .andExpect(jsonPath("$.data[?(@ == 'like-cache-bypass')]").exists())
                .andExpect(jsonPath("$.data[?(@ == 'merchant-detail-new')]").exists());
    }

    @Test
    @DisplayName("GET /admin/feature-flag/recommend-v2 → 200,响应体是 FlagConfig(mode=PERCENTAGE)")
    void get_existingFlag_returnsConfig() throws Exception {
        FlagConfig config = FlagConfig.percentage(20);
        when(featureFlagService.getConfig("recommend-v2")).thenReturn(config);

        mockMvc.perform(get("/admin/feature-flag/recommend-v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mode").value("PERCENTAGE"))
                .andExpect(jsonPath("$.data.percentage").value(20));
    }

    @Test
    @DisplayName("GET /admin/feature-flag/unknown → 404,message 含 feature flag not found")
    void get_unknownFlag_returns404() throws Exception {
        when(featureFlagService.getConfig("unknown")).thenReturn(null);

        mockMvc.perform(get("/admin/feature-flag/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("feature flag not found")));
    }

    @Test
    @DisplayName("POST /admin/feature-flag/recommend-v2(合法 FlagConfig)→ 200,Service.setConfig 被调用")
    void set_validConfig_callsServiceSetConfig() throws Exception {
        String body = "{\"mode\":\"PERCENTAGE\",\"percentage\":30}";

        mockMvc.perform(post("/admin/feature-flag/recommend-v2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mode").value("PERCENTAGE"))
                .andExpect(jsonPath("$.data.percentage").value(30));

        // Service 收到的 config 必须是合法 FlagConfig(mode=PERCENTAGE, percentage=30)
        org.mockito.ArgumentCaptor<FlagConfig> captor =
                org.mockito.ArgumentCaptor.forClass(FlagConfig.class);
        verify(featureFlagService, times(1))
                .setConfig(eq("recommend-v2"), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getMode())
                .isEqualTo(com.meisijiya.campusfood.module.featureflag.FlagMode.PERCENTAGE);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPercentage())
                .isEqualTo(30);
    }

    @Test
    @DisplayName("POST /admin/feature-flag/recommend-v2(percentage=-1 非法)→ 400,Service.setConfig 不被调用")
    void set_invalidPercentage_returns400() throws Exception {
        String body = "{\"mode\":\"PERCENTAGE\",\"percentage\":-1}";

        mockMvc.perform(post("/admin/feature-flag/recommend-v2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("flag config invalid")));

        verify(featureFlagService, never()).setConfig(any(), any());
    }
}