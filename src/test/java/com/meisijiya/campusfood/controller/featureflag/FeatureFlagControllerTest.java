package com.meisijiya.campusfood.controller.featureflag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.meisijiya.campusfood.config.JwtAuthenticationFilter;
import com.meisijiya.campusfood.module.auth.JwtService;
import com.meisijiya.campusfood.module.featureflag.FeatureFlagService;
import com.meisijiya.campusfood.module.featureflag.FlagConfig;
import com.meisijiya.campusfood.module.ratelimit.RateLimitDegradationMonitor;
import com.meisijiya.campusfood.module.ratelimit.RateLimitFilter;

/**
 * F-11 W2:FeatureFlagController 单元测试 — 仅 web 层 + MockMvc,
 * 不连真实 Redis(Spring bean {@code featureFlagService} 已被 MockitoBean 替换)。
 *
 * <h2>测试隔离策略</h2>
 * <p>{@code @WebMvcTest} 默认会扫描 Filter 类型 bean({@link JwtAuthenticationFilter}、
 * {@link RateLimitFilter})。这俩 Filter 各自的依赖链很深({@code JwtService} / 4 个
 * {@code RateLimiter} bean / {@link RateLimitDegradationMonitor} / Redis),在
 * 不引入 SecurityAutoConfiguration / Redis 的前提下没法干净启动。
 * <p>本测试:
 * <ol>
 *   <li>排除 {@link SecurityAutoConfiguration}(避免 {@code UserDetailsService}
 *       依赖链炸);</li>
 *   <li>{@code @MockitoBean} mock 两个 servlet Filter 本体,让 Spring 不去实例化
 *       它们的依赖;</li>
 *   <li>{@code @MockitoBean JwtService} 以防 W3 范围之外的别的依赖;</li>
 *   <li>{@code @AutoConfigureMockMvc(addFilters = false)} 关闭 servlet filter chain,
 *       反正 controller 类上没有 {@code @PreAuthorize},生产里也是 {@code permitAll}。</li>
 * </ol>
 *
 * @author meisijiya
 */
@WebMvcTest(controllers = FeatureFlagController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class FeatureFlagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeatureFlagService featureFlagService;

    // 以下 MockitoBean 仅为"占住" Filter 类型 bean 的依赖注入路径,使 Spring 上下文能干净启动;
    // 本测试不连真实 Redis / JwtService / RateLimiter,实际请求走 addFilters=false 跳过 servlet chain。
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private RateLimitFilter rateLimitFilter;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private RateLimitDegradationMonitor rateLimitDegradationMonitor;

    @Test
    @DisplayName("GET /api/feature-flag/recommend-v2/check?studentId=100 → 200,enabled=true")
    void check_withStudentId_returnsEnabledTrue() throws Exception {
        when(featureFlagService.getConfig("recommend-v2"))
                .thenReturn(FlagConfig.percentage(20));
        when(featureFlagService.isEnabled(eq("recommend-v2"), eq(100L)))
                .thenReturn(true);

        mockMvc.perform(get("/api/feature-flag/recommend-v2/check")
                        .param("studentId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.flagKey").value("recommend-v2"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.mode").value("PERCENTAGE"))
                .andExpect(jsonPath("$.data.studentId").value(100));
    }

    @Test
    @DisplayName("GET /api/feature-flag/unknown/check → 404,message 含 feature flag not found")
    void check_unknownFlag_returns404() throws Exception {
        when(featureFlagService.getConfig("unknown")).thenReturn(null);

        mockMvc.perform(get("/api/feature-flag/unknown/check")
                        .param("studentId", "100"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("feature flag not found")));

        // isEnabled 不应被调用(controller 在 getConfig 返 null 后短路返 404)
        verify(featureFlagService, never()).isEnabled(any(), any());
    }

    @Test
    @DisplayName("GET /api/feature-flag/recommend-v2/check(studentId 缺失)→ 200,studentId=null")
    void check_withoutStudentId_returns200WithNullStudentId() throws Exception {
        when(featureFlagService.getConfig("recommend-v2"))
                .thenReturn(FlagConfig.allOn());
        when(featureFlagService.isEnabled(eq("recommend-v2"), eq(null)))
                .thenReturn(true);

        mockMvc.perform(get("/api/feature-flag/recommend-v2/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.flagKey").value("recommend-v2"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.mode").value("ALL_ON"))
                // studentId 字段是 Long(对象类型)— Jackson 序列化为 null
                .andExpect(jsonPath("$.data.studentId").doesNotExist());

        verify(featureFlagService).isEnabled("recommend-v2", null);
    }
}