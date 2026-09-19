package com.meisijiya.campusfood.module.ratelimit;

/**
 * F-7 限流器抽象 — 双层令牌桶共享同一接口契约。
 *
 * <p>本接口由 W1(F-7 W1)定义,W1 负责 Redis Lua 实现
 * ({@code RedisTokenBucket}),W2(F-7 W2)负责 Caffeine 进程内降级实现
 * ({@code CaffeineLocalBucket});{@code RateLimitFilter} 只看到接口,
 * 不知道具体实现,可透明降级。
 *
 * <h2>合约</h2>
 * <ul>
 *   <li>{@link #tryAcquire(String)} 必须线程安全 — Redis 走 Lua 原子脚本,
 *       Caffeine 走 lock-free CAS(由实现保证)。</li>
 *   <li>实现遇到后端不可达(Redis 连接失败 / Lua 抛异常)必须抛
 *       {@link RateLimiterBackendException},<strong>不要返回 false</strong> —
 *       false 是"业务拒绝"(token 不足),异常是"系统降级",Filter 端用 try/catch 分流。</li>
 *   <li>{@code permits=1}(每次请求 1 个 token)是 F-7 的简化口径,接口保留 {@code permits}
 *       字段以备未来 burst read 场景。</li>
 * </ul>
 *
 * @author meisijiya
 */
public interface RateLimiter {

    /**
     * 尝试获取 1 个令牌。
     *
     * @param bucketKey 桶名(user:{sid} 或 api:{endpoint})
     * @return true=放行,false=限流拒绝
     * @throws RateLimiterBackendException 后端不可达,Filter 端应切 fallback
     */
    default boolean tryAcquire(String bucketKey) {
        return tryAcquire(bucketKey, 1);
    }

    /**
     * 尝试获取 {@code permits} 个令牌。
     *
     * @param bucketKey 桶名
     * @param permits   本次请求消耗的令牌数(通常 1)
     * @return true=放行,false=限流拒绝
     * @throws RateLimiterBackendException 后端不可达
     */
    boolean tryAcquire(String bucketKey, int permits);
}
