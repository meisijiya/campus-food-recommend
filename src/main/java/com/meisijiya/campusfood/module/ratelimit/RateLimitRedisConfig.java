package com.meisijiya.campusfood.module.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * F-7 W1:Redis 主桶 {@link RateLimiter} bean 声明 — 与 W2 的
 * {@link RateLimitFallbackConfig} 对称,各声明 2 个 bean(per layer)。
 *
 * <h2>Bean 命名契约(W2 Filter 必须按此名注入)</h2>
 * <ul>
 *   <li>{@code redisUserRateLimiter} — 用户级桶,capacity=100 / refill=10/s;
 *       key 形如 {@code user:<sid>}。</li>
 *   <li>{@code redisApiRateLimiter} — API 全局桶,capacity=1000 / refill=500/s;
 *       key 形如 {@code api:<METHOD>:<URI>}。</li>
 * </ul>
 *
 * <h2>与 W2 的对称</h2>
 * <p>W2 的 {@link RateLimitFallbackConfig} 声明 {@code caffeineUserRateLimiter} /
 * {@code caffeineApiRateLimiter} 2 个 Caffeine bean。<strong>本类声明的是 Redis 主路径</strong>,
 * Filter 串联两层:Redis 抛 {@link RateLimiterBackendException} → 切 Caffeine。
 *
 * @author meisijiya
 */
@Configuration
public class RateLimitRedisConfig {

    /**
     * Redis 用户级令牌桶 — 单次能瞬时通过 100 个请求,稳态 10 tokens/s 补充。
     */
    @Bean(name = "redisUserRateLimiter")
    public RateLimiter redisUserRateLimiter(
            StringRedisTemplate redis,
            RateLimitProperties properties) {
        return new RedisTokenBucket(
                redis,
                properties,
                properties.getUser().getBurst(),
                properties.getUser().getRate());
    }

    /**
     * Redis API 全局令牌桶 — 单次能瞬时通过 1000 个请求,稳态 500 tokens/s 补充。
     */
    @Bean(name = "redisApiRateLimiter")
    public RateLimiter redisApiRateLimiter(
            StringRedisTemplate redis,
            RateLimitProperties properties) {
        return new RedisTokenBucket(
                redis,
                properties,
                properties.getApi().getBurst(),
                properties.getApi().getRate());
    }
}
