package com.meisijiya.campusfood.module.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * F-7 限流集成测试(W3)— 端到端验证双层令牌桶(API 全局 + 用户级)HTTP 行为契约。
 *
 * <h2>覆盖范围</h2>
 * <ul>
 *   <li>{@link #limit_triggers_429_after_burst_exhausted()} — Redis up 时,用户级桶 burst 耗尽后返 429</li>
 *   <li>{@link #redisDown_fallsBackToCaffeine_andDoesNotReturn500()} — Redis 容器停止后,
 *       {@link RateLimitFilter} 切到 {@link CaffeineLocalBucket} 兜底,请求继续成功(非 500)</li>
 *   <li>{@link #rejected_returns_429_with_RetryAfter_Header_and_JsonBody()} — 拒绝响应必须含
 *       {@code Retry-After} header + JSON body {@code code=42900}</li>
 * </ul>
 *
 * <h2>为什么用 MockMvc + 真实 Testcontainers Redis</h2>
 * <p>W3 不写生产代码,只验证 W1 + W2 实现的 HTTP 行为契约。Redis Lua 原子性已经在
 * W1 的单元测试覆盖,本 IT 只覆盖端到端 "登录拿 sid → 用户级桶 key 实际写 Redis →
 * 桶满后 429"。这样 W1 / W2 重构内部实现时本 IT 不需要改。
 *
 * <h2>端点选择策略</h2>
 * <p>{@link RateLimitFilter#shouldNotFilter} 显式跳过 {@code /api/auth/**} 与
 * {@code /actuator/**} — 登录自己不能被自己限流,k8s 探针被限流会触发 pod 误杀。
 * 因此本 IT 走 "先 POST /api/auth/login 拿 token → GET /api/merchant/M-NOODLE 触发限流"
 * 路径,与 F-5 evidence 的 {@code locustfile_mix.py} 选 endpoint 的口径一致。
 *
 * <h2>关键测试参数(降 burst 以快速触发 429)</h2>
 * <p>默认 user-burst=100 / rate=10/s,1 秒内连发 5 次才能耗光。这里用 {@code @TestPropertySource}
 * 把 user-burst 压到 3,1 秒内就能稳定触发 429,避免 flaky。
 * {@code spring.data.redis.timeout=500ms} 配合 Lettuce 让 "Redis 停止" 探测在 1s 内完成,
 * 避免 Lettuce 默认 60s 超时把测试拉成 1 分钟。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>本 IT 跑通 → 3 个 acceptance #6 / #7 / #8 端到端验证</li>
 *   <li>Redis 状态:用例 1 / 3 期望 burst=3 后第 4 次必 429(用户桶单独维护,用例间独立)</li>
 *   <li>MySQL 容器不开:登录走 InMemoryUserDetailsManager 内存用户表,仅 Redis 必须真实</li>
 * </ul>
 *
 * <p>本地需 Docker daemon 运行(Testcontainers 自起 redis:7.4-alpine,与 compose 不冲突)。
 *
 * @author meisijiya
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "rate-limit.user.burst=3",
        "rate-limit.user.rate=1",
        "rate-limit.api.burst=1000",
        "rate-limit.api.rate=500",
        "spring.data.redis.timeout=500ms",
        "spring.data.redis.connect-timeout=500ms",
        "spring.data.redis.lettuce.shutdown-timeout=200ms",
        "management.health.redis.enabled=true"
})
@Testcontainers
class RateLimitIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired
    private MockMvc mockMvc;

    /** 登录拿 sid — 返回 Bearer token。/api/auth/login 不被 RateLimitFilter 限流。 */
    private String loginAsDemo() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"demo\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        int idx = body.indexOf("\"accessToken\":\"");
        assertThat(idx).as("login response must contain accessToken").isGreaterThan(-1);
        int start = idx + "\"accessToken\":\"".length();
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }

    /** 单次请求带 Bearer 调 GET /api/merchant/M-NOODLE。 */
    private org.springframework.test.web.servlet.ResultActions callMerchant(String token) throws Exception {
        return mockMvc.perform(get("/api/merchant/M-NOODLE")
                .header("Authorization", "Bearer " + token));
    }

    // ---------- acceptance #6:Redis up 时,限流生效 → 200 → 429 过渡 ----------

    @Test
    @DisplayName("rateLimit_userBurst3_first3ReturnNon429_fourthReturns429")
    void limit_triggers_429_after_burst_exhausted() throws Exception {
        String token = loginAsDemo();

        for (int i = 0; i < 3; i++) {
            callMerchant(token)
                    .andExpect(status().is(org.hamcrest.Matchers.not(429)));
        }

        callMerchant(token)
                .andExpect(status().is(429));
    }

    // ---------- acceptance #7:Redis down → Caffeine 降级 → 不返 500 ----------

    @Test
    @DisplayName("rateLimit_redisStopped_fallsBackToCaffeine_requestsDoNotReturn500")
    void redisDown_fallsBackToCaffeine_andDoesNotReturn500() throws Exception {
        String token = loginAsDemo();
        callMerchant(token).andExpect(status().is(org.hamcrest.Matchers.not(500)));

        REDIS.stop();

        Thread.sleep(1500);

        for (int i = 0; i < 3; i++) {
            int status = callMerchant(token).andReturn().getResponse().getStatus();
            assertThat(status)
                    .as("Redis-down requests must NOT return 500 (Caffeine fallback); got status=%d on attempt=%d", status, i)
                    .isNotEqualTo(500);
        }
    }

    // ---------- acceptance #8:429 响应必含 Retry-After header + JSON code=42900 ----------

    @Test
    @DisplayName("rateLimit_rejected_429_hasRetryAfterHeader_andJsonBodyCode42900")
    void rejected_returns_429_with_RetryAfter_Header_and_JsonBody() throws Exception {
        String token = loginAsDemo();

        for (int i = 0; i < 3; i++) {
            callMerchant(token);
        }

        callMerchant(token)
                .andExpect(status().is(429))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.not("")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message").exists());
    }
}