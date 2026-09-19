package com.meisijiya.campusfood.module.ratelimit;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.StreamUtils;

/**
 * Redis 令牌桶限流实现(F-7 W1)— 通过 {@code token-bucket.lua} 在 Redis 内单线程
 * 原子完成"读桶 → 计算 refill → 决定放行 → 写回"四步。
 *
 * <h2>实现 W2 的 {@link RateLimiter} 契约</h2>
 * <p>实现{@link RateLimiter#tryAcquire(String, int)} 接口 — {@code permits} 是本次请求消耗
 * 令牌数(默认 1)。每个 {@code RedisTokenBucket} 实例绑死一对参数
 * ({@code capacity} / {@code refillTokensPerSecond}),由 {@link RateLimitRedisConfig} 在
 * Spring 容器中按 layer(user / api)分别声明 bean。
 *
 * <h2>降级契约(关键 — 与 W2 Filter 配合)</h2>
 * <p>Redis 调用异常时 <strong>必须抛 {@link RateLimiterBackendException}</strong>,由
 * W2 的 {@code RateLimitFilter} 在 catch 块切到 Caffeine 进程内桶兜底,且自增
 * {@code rate_limiter_degraded_total} Counter — 显式降级语义,绝不返回 {@code false}
 * 假装是业务拒绝。
 *
 * <h2>结果表协议</h2>
 * <p>Lua 脚本返回 {@code {allowed, tokens_left, retry_after_ms}},对应到 Java 端
 * {@link Decision} record。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>不带 Lombok,字段全包私有 + 显式构造器注入(F-1 起的项目惯例)。</li>
 *   <li>无 {@code @Component} 注解 — bean 由 {@link RateLimitRedisConfig} 显式声明。</li>
 *   <li>Lua 脚本路径由 {@link RateLimitProperties#getScriptLocation()} 控制,默认 {@code scripts/}。</li>
 *   <li>{@code permits ≤ 0} 抛 {@link IllegalArgumentException}。</li>
 * </ul>
 *
 * @author meisijiya
 */
public class RedisTokenBucket implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucket.class);

    /** Lua 脚本文件名(classpath:scripts/ 目录下)。 */
    static final String SCRIPT_NAME = "token-bucket.lua";

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    /** 本实例绑定的桶容量(单次能瞬时通过的最大请求数)— user 桶 100 / api 桶 1000。 */
    private final int capacity;
    /** 本实例绑定的稳态补充速率(每秒补充令牌数)— user 桶 10/s / api 桶 500/s。 */
    private final double refillTokensPerSecond;
    /** Lua 脚本句柄 — 构造器内同步从 classpath 加载,失败抛 {@link IllegalStateException}。 */
    private final RedisScript<List> script;

    public RedisTokenBucket(StringRedisTemplate redis,
                            RateLimitProperties properties,
                            int capacity,
                            double refillTokensPerSecond) {
        if (redis == null) {
            throw new IllegalArgumentException("redis must be non-null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must be non-null");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        if (refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("refillTokensPerSecond must be > 0, got " + refillTokensPerSecond);
        }
        this.redis = redis;
        this.properties = properties;
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.script = loadScript(properties.getScriptLocation() + SCRIPT_NAME);
        log.info("RedisTokenBucket initialized: capacity={}, refill={} tokens/s, script={}",
                capacity, refillTokensPerSecond, properties.getScriptLocation() + SCRIPT_NAME);
    }

    /**
     * 从 classpath 加载 Lua 脚本到 {@link DefaultRedisScript}。失败立即抛
     * {@link IllegalStateException},让 Spring 上下文启动失败 — 宁可启动崩,也不要
     * "脚本路径写错但 Redis 调用全失败"这种悄悄上线的状态。
     */
    private static RedisScript<List> loadScript(String classpathPath) {
        try (var in = new ClassPathResource(classpathPath).getInputStream()) {
            String body = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            DefaultRedisScript<List> s = new DefaultRedisScript<>();
            s.setScriptText(body);
            s.setResultType(List.class);
            return s;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Lua script: " + classpathPath, e);
        }
    }

    // ---------- 接口实现 ----------

    @Override
    public boolean tryAcquire(String bucketKey, int permits) {
        if (bucketKey == null || bucketKey.isBlank()) {
            throw new IllegalArgumentException("bucketKey must be non-blank");
        }
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0, got " + permits);
        }
        Decision d = executeDetailed(bucketKey, permits);
        return d.allowed();
    }

    // ---------- 详细决策路径(给 W2 Filter / 监控 / 测试用) ----------

    public Decision tryAcquireDetailed(String bucketKey, int permits) {
        if (bucketKey == null || bucketKey.isBlank()) {
            throw new IllegalArgumentException("bucketKey must be non-blank");
        }
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0, got " + permits);
        }
        return executeDetailed(bucketKey, permits);
    }

    // ---------- 内部 ----------

    private Decision executeDetailed(String bucketKey, int permits) {
        long nowMs = System.currentTimeMillis();
        try {
            @SuppressWarnings("unchecked")
            List<Object> raw = redis.execute(
                    script,
                    List.of(bucketKey),
                    Integer.toString(capacity),
                    Double.toString(refillTokensPerSecond),
                    Long.toString(nowMs),
                    Integer.toString(permits));
            return parseDecision(raw, bucketKey);
        } catch (Exception e) {
            throw new RateLimiterBackendException(
                    "Redis token-bucket script execution failed for key=" + bucketKey, e);
        }
    }

    private static Decision parseDecision(List<Object> raw, String bucketKey) {
        if (raw == null || raw.size() < 3) {
            throw new RateLimiterBackendException(
                    "Malformed token-bucket.lua result for key=" + bucketKey
                            + ", size=" + (raw == null ? 0 : raw.size()));
        }
        long allowedLong = ((Number) raw.get(0)).longValue();
        double tokensLeft = Double.parseDouble((String) raw.get(1));
        long retryAfterRaw = ((Number) raw.get(2)).longValue();
        long retryAfterMs = Math.max(0L, retryAfterRaw);
        return new Decision(allowedLong == 1L, tokensLeft, retryAfterMs);
    }

    public int capacity() {
        return capacity;
    }

    public double refillTokensPerSecond() {
        return refillTokensPerSecond;
    }

    /**
     * 限流详细决策记录 — W2 的 Filter 在 HTTP 429 响应里会用 {@code retryAfterMs} 构造
     * {@code Retry-After} header。
     */
    public record Decision(boolean allowed, double tokensLeft, long retryAfterMs) {
    }
}
