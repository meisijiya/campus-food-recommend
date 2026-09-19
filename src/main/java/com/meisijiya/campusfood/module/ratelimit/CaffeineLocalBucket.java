package com.meisijiya.campusfood.module.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F-7 W2:本地降级令牌桶 — Redis 不可达时 JVM-local 兜底。
 *
 * <p>本实现作为 {@link RateLimiter} 的 fallback:
 * <ol>
 *   <li>主路径走 {@code RedisTokenBucket}(W1);Redis 返回 false 是业务拒绝,
 *       返回抛 {@link RateLimiterBackendException} 时 Filter 切到本类。</li>
 *   <li>本类是 lock-free lazy-refill token bucket — 经典算法,
 *       用 {@link AtomicReference}{@code <BucketState>} + CAS 实现并发,
 *       没有任何 synchronized / ReentrantLock,确保 1w+ QPS 下不退化。</li>
 *   <li>每个桶独立 state,持有者用 Caffeine {@code expireAfterAccess=10min}
 *       防止长期无访问的桶无限堆积内存。</li>
 * </ol>
 *
 * <h2>Redis 恢复后的异步回写</h2>
 * <p>scope 要求"Redis 恢复后异步回写"。由于本类是 fallback(被动接管),
 * 不是主动回写 — 它不知道 Redis 是否已恢复。本类不主动尝试回连 Redis;
 * Filter 端持有 {@code degraded} 状态,Redis 恢复的探测由 W1 的
 * {@code RedisTokenBucket} 自己在下一次 tryAcquire 时通过 catch 异常
 * "没异常 = 已恢复"判定。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>{@code capacity > 0},{@code refillTokensPerSecond > 0} — 构造器校验</li>
 *   <li>{@code permits > 0} — 业务侧保证</li>
 *   <li>本类<strong>永远不抛</strong> {@link RateLimiterBackendException} —
 *       进程内计算无外部依赖,语义失败只有"业务拒绝"。</li>
 * </ul>
 *
 * @author meisijiya
 */
public class CaffeineLocalBucket implements RateLimiter {

    /** bucket 空闲 10 分钟后从 Caffeine 淘汰,防止恶意 unique key 撑爆内存。 */
    private static final Duration BUCKET_IDLE_TTL = Duration.ofMinutes(10);

    /** Caffeine 容量上限 — 即使每条请求一个 unique key,上限也是 10w,足够兜底。 */
    private static final int MAX_BUCKETS = 100_000;

    private final int capacity;
    private final double refillTokensPerSecond;
    private final Cache<String, AtomicReference<BucketState>> buckets;

    /**
     * @param capacity              桶容量(burst)— 单次能瞬时通过的最大请求数
     * @param refillTokensPerSecond 稳态速率 — 每秒补充的令牌数
     */
    public CaffeineLocalBucket(int capacity, double refillTokensPerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        if (refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("refillTokensPerSecond must be > 0, got " + refillTokensPerSecond);
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS)
                .expireAfterAccess(BUCKET_IDLE_TTL)
                .build();
    }

    @Override
    public boolean tryAcquire(String bucketKey, int permits) {
        if (permits <= 0) {
            // permits=0 在接口层默认走 1 路径,但显式传 0 也按"放行"语义处理
            return true;
        }
        AtomicReference<BucketState> holder = buckets.get(bucketKey, k -> new AtomicReference<>(BucketState.full(capacity)));
        // CAS 循环:每次重试前重算 refill(基于最新 nowNanos)
        while (true) {
            long nowNanos = System.nanoTime();
            BucketState current = holder.get();
            double refilled = current.tokens
                    + (nowNanos - current.lastRefillNanos) / 1_000_000_000.0 * refillTokensPerSecond;
            double capped = Math.min(refilled, capacity);
            if (capped < permits) {
                // 令牌不足 — 不更新 state(避免无意义写),直接拒绝
                return false;
            }
            double remaining = capped - permits;
            BucketState next = new BucketState(remaining, nowNanos);
            if (holder.compareAndSet(current, next)) {
                return true;
            }
            // CAS 失败 → 重试(其他线程已更新)
        }
    }

    /**
     * 桶内部状态 — 不可变 record 类,CAS 更新靠 {@code AtomicReference}.
     *
     * @param tokens          当前可用令牌数(可能 > capacity,因为 refill 在 CAS 之间不重复扣)
     * @param lastRefillNanos 上次 refill 计算时刻(nanos)
     */
    private record BucketState(double tokens, long lastRefillNanos) {
        static BucketState full(int capacity) {
            return new BucketState(capacity, System.nanoTime());
        }
    }
}
