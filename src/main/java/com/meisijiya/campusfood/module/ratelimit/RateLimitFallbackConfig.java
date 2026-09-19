package com.meisijiya.campusfood.module.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * F-7 W2:Caffeine 降级桶 {@link RateLimiter} bean 声明 — Redis 不可达时兜底。
 *
 * <p>本配置声明 2 个 {@link CaffeineLocalBucket} bean,按 layer(user / api)区分参数:
 * <ul>
 *   <li>{@code caffeineUserRateLimiter} — capacity=100, refill=10/s(对应 user 桶默认值)</li>
 *   <li>{@code caffeineApiRateLimiter} — capacity=1000, refill=500/s(对应 api 桶默认值)</li>
 * </ul>
 *
 * <h2>与 W1 的契约</h2>
 * <p>W1 创建 2 个 Redis {@link RateLimiter} bean:
 * <ul>
 *   <li>{@code redisUserRateLimiter} — 同 capacity / refill</li>
 *   <li>{@code redisApiRateLimiter} — 同 capacity / refill</li>
 * </ul>
 * W2 的 {@code RateLimitFilter} 用 {@code @Qualifier} 注入这 4 个 bean。
 *
 * <h2>application.yml key 对齐</h2>
 * <p>参数直接读 {@link RateLimitProperties}({@code rate-limit.*} 前缀),W1 在
 * {@code application.yml} 用相同 key 名 — 这样 Caffeine 与 Redis 拿到一致参数,
 * 降级后限流行为不漂移。
 *
 * @author meisijiya
 */
@Configuration
public class RateLimitFallbackConfig {

    @Bean(name = "caffeineUserRateLimiter")
    public RateLimiter caffeineUserRateLimiter(RateLimitProperties properties) {
        return new CaffeineLocalBucket(
                properties.getUser().getBurst(),
                properties.getUser().getRate());
    }

    @Bean(name = "caffeineApiRateLimiter")
    public RateLimiter caffeineApiRateLimiter(RateLimitProperties properties) {
        return new CaffeineLocalBucket(
                properties.getApi().getBurst(),
                properties.getApi().getRate());
    }
}
