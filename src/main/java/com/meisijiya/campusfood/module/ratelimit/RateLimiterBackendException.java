package com.meisijiya.campusfood.module.ratelimit;

/**
 * F-7 限流后端不可达异常 — Redis 连接失败 / Lua 脚本异常时由
 * {@link RateLimiter} 实现抛出。
 *
 * <p>本异常区分于"业务拒绝"({@code tryAcquire} 返回 false)。
 * 业务拒绝是 token 数耗尽(正常限流行为),本异常是系统降级:
 * 限流后端故障,Filter 应切换到 Caffeine 本地桶继续兜底,
 * 而非直接 500 暴露给调用方。
 *
 * <p>W1 的 {@code RedisTokenBucket} 在 {@code StringRedisTemplate.execute(...)}
 * 抛 {@code RedisConnectionFailureException} 等子类时包装为本异常;
 * W2 的 {@code RateLimitFilter} 在 catch 块调用 CaffeineLocalBucket。
 *
 * @author meisijiya
 */
public class RateLimiterBackendException extends RuntimeException {

    public RateLimiterBackendException(String message, Throwable cause) {
        super(message, cause);
    }

    public RateLimiterBackendException(String message) {
        super(message);
    }
}
