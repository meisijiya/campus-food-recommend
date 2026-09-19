package com.meisijiya.campusfood.module.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meisijiya.campusfood.common.ApiResponse;
import com.meisijiya.campusfood.common.exception.ErrorCode;
import com.meisijiya.campusfood.module.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-7 W2:RateLimitFilter 单测 — MockMvc standalone 模式,验证 429 + Retry-After + 降级路径。
 *
 * <p>6 个场景:
 * <ol>
 *   <li>happy path:两层都放行 → 200 + 透传到 controller</li>
 *   <li>API 全局桶拒绝 → 429 + Retry-After:1 + JSON 42900 body(user 桶不应被查)</li>
 *   <li>用户级桶拒绝 → 429(api 通过、user 拒绝)</li>
 *   <li>API 桶 Redis 抛 BackendException → 切 Caffeine(Caffeine 放行 → 200)</li>
 *   <li>两层 Caffeine 也拒 → 429(degradation counter 自增)</li>
 *   <li>{@code /api/auth/**} 路径完全不被过滤(应到达 controller)</li>
 * </ol>
 *
 * @author meisijiya
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock(name = "redisUserRateLimiter") RateLimiter redisUserLimiter;
    @Mock(name = "redisApiRateLimiter") RateLimiter redisApiLimiter;
    @Mock(name = "caffeineUserRateLimiter") RateLimiter caffeineUserLimiter;
    @Mock(name = "caffeineApiRateLimiter") RateLimiter caffeineApiLimiter;
    @Mock RateLimitDegradationMonitor degradationMonitor;
    @Mock JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        RateLimitFilter filter = new RateLimitFilter(
                redisUserLimiter, redisApiLimiter,
                caffeineUserLimiter, caffeineApiLimiter,
                degradationMonitor, jwtService, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestProbeController())
                .addFilters(filter)
                .build();
    }

    @Test
    void happyPath_bothLayersAllow_returns200() throws Exception {
        when(redisApiLimiter.tryAcquire(anyString())).thenReturn(true);
        when(redisUserLimiter.tryAcquire(anyString())).thenReturn(true);

        mockMvc.perform(get("/api/test/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("probe-ok"));

        verify(redisApiLimiter, times(1)).tryAcquire(anyString());
        verify(redisUserLimiter, times(1)).tryAcquire(anyString());
        // 放行 → 不应触发降级
        verify(degradationMonitor, never()).recordDegradation(anyString());
    }

    @Test
    void apiBucketRejected_returns429_withRetryAfter_andJsonBody() throws Exception {
        when(redisApiLimiter.tryAcquire(anyString())).thenReturn(false);

        mockMvc.perform(get("/api/test/probe"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.RATE_LIMITED))
                .andExpect(jsonPath("$.message").value("rate limited"));

        // 短路:API 拒后不应查 user 桶
        verify(redisUserLimiter, never()).tryAcquire(anyString());
        verify(caffeineApiLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void userBucketRejected_apiAllowed_returns429() throws Exception {
        when(redisApiLimiter.tryAcquire(anyString())).thenReturn(true);
        when(redisUserLimiter.tryAcquire(anyString())).thenReturn(false);

        mockMvc.perform(get("/api/test/probe"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value(ErrorCode.RATE_LIMITED));

        // api 通过 → caffeine api 不应被调用
        verify(caffeineApiLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void redisApiThrows_fallsBackToCaffeine_caffeineAllows_requestProceeds() throws Exception {
        when(redisApiLimiter.tryAcquire(anyString()))
                .thenThrow(new RateLimiterBackendException("redis down", new RuntimeException("connection refused")));
        when(caffeineApiLimiter.tryAcquire(anyString())).thenReturn(true);
        when(redisUserLimiter.tryAcquire(anyString())).thenReturn(true);

        mockMvc.perform(get("/api/test/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("probe-ok"));

        // 降级计数器应被触发一次(redis api → caffeine api)
        verify(degradationMonitor, times(1))
                .recordDegradation(RateLimitDegradationMonitor.SOURCE_REDIS_TO_CAFFEINE);
    }

    @Test
    void bothRedisAndCaffeineReject_returns429_andCounterIncrements() throws Exception {
        when(redisApiLimiter.tryAcquire(anyString()))
                .thenThrow(new RateLimiterBackendException("redis down", new RuntimeException("conn refused")));
        when(caffeineApiLimiter.tryAcquire(anyString())).thenReturn(false);

        mockMvc.perform(get("/api/test/probe"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.RATE_LIMITED));

        verify(degradationMonitor, times(1))
                .recordDegradation(RateLimitDegradationMonitor.SOURCE_REDIS_TO_CAFFEINE);
        // user 桶不应被查(api 层已拒)
        verify(redisUserLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void authPath_bypassesFilter_reachesController() throws Exception {
        // /api/auth/** 不应触发 Filter — controller 直接返回,RateLimiter 不被调用
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"u\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("login-stub"));

        verify(redisApiLimiter, never()).tryAcquire(anyString());
        verify(redisUserLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void responseCode42900_matchesErrorCodeConstant() {
        // 防御性断言:ErrorCode.RATE_LIMITED = 42900(由 W2 新增)
        // 防止有人不小心把常量改了导致 JSON 与 acceptance criteria 不一致
        ApiResponse<Void> body = ApiResponse.fail(ErrorCode.RATE_LIMITED, "rate limited");
        assert body.code() == 42900 : "ErrorCode.RATE_LIMITED must equal 42900";
    }

    /**
     * 测试探针 controller — 提供 2 个端点:probe(过限流)+ login-stub(放行路径)。
     */
    @RestController
    static class TestProbeController {

        @GetMapping("/api/test/probe")
        public String probe() {
            return "probe-ok";
        }

        @PostMapping("/api/auth/login")
        public String login(@RequestBody(required = false) String body) {
            return "login-stub";
        }
    }
}
