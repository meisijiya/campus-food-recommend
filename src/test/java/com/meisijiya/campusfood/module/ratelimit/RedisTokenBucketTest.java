package com.meisijiya.campusfood.module.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.meisijiya.campusfood.module.ratelimit.RedisTokenBucket.Decision;

/**
 * {@link RedisTokenBucket} 单元测试(F-7 W1)— 覆盖 8 路径:首次放行 / 二次拒绝 /
 * 令牌 refill 后再放行 / Redis 异常抛 {@link RateLimiterBackendException} /
 * 自定义 permits / 默认 permits=1 / 参数校验 / 构造器校验 / 脚本路径错误处理。
 *
 * <p>测试纪律:Mock {@link StringRedisTemplate},不连真实 Redis;Lua 返回值通过 mock
 * {@code redis.execute(RedisScript, List, Object...)} 直接构造;关键参数(keys / args)
 * 用 {@link ArgumentCaptor} 抓取断言。
 *
 * @author meisijiya
 */
class RedisTokenBucketTest {

    private StringRedisTemplate redis;
    private RateLimitProperties properties;
    /** 测试用 RedisTokenBucket — capacity=100 / refill=10/s(模拟 user 桶默认值)。 */
    private RedisTokenBucket bucket;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        properties = new RateLimitProperties();
        bucket = new RedisTokenBucket(redis, properties, 100, 10.0);
    }

    // ---------- 接口契约:tryAcquire(String, int permits) ----------

    @Test
    @DisplayName("tryAcquire_Lua返allowed=1_tokens大于permits_返回true_keys正确_args4个")
    void tryAcquire_firstCall_luaReturnsAllowed1_returnsTrue() {
        // Lua 返回 {allowed=1, tokens="99.500000", retry_after=0}
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, "99.500000", 0L));

        boolean result = bucket.tryAcquire("user:s-1", 1);

        assertThat(result).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly("user:s-1");
        // args 顺序:ARGV[1]=burst(100), ARGV[2]=rate(10.0), ARGV[3]=now_ms, ARGV[4]=permits(1)
        assertThat(argsCaptor.getValue()).hasSize(4);
        assertThat(argsCaptor.getValue()[0]).isEqualTo("100");
        assertThat(argsCaptor.getValue()[1]).isEqualTo("10.0");
        assertThat(argsCaptor.getValue()[3]).isEqualTo("1");
    }

    @Test
    @DisplayName("tryAcquire_同key第二次_Lua返allowed=0_返回false")
    void tryAcquire_secondCall_luaReturnsAllowed0_returnsFalse() {
        // 桶空:retry_after_ms = 100ms(rate=10/s → 1/10s = 100ms)
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, "0.0", 100L));

        boolean result = bucket.tryAcquire("user:s-1", 1);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("tryAcquire_令牌refill后_Lua再返allowed=1_返回true")
    void tryAcquire_afterRefill_luaReturnsAllowed1Again_returnsTrue() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, "0.0", 100L))
                .thenReturn(List.of(1L, "99.500000", 0L));

        boolean first = bucket.tryAcquire("user:s-1", 1);
        boolean second = bucket.tryAcquire("user:s-1", 1);
        assertThat(first).isFalse();
        assertThat(second).isTrue();
    }

    @Test
    @DisplayName("tryAcquire_Redis抛RuntimeException_包装为RateLimiterBackendException_让W2切换降级")
    void tryAcquire_redisException_wrapsAsRateLimiterBackendException() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("Redis command timed out"));

        // 必须抛 RateLimiterBackendException(不是返回 false,不是 fail-open),让 W2 的
        // RateLimitFilter 在 catch 块切 Caffeine 降级桶兜底
        assertThatThrownBy(() -> bucket.tryAcquire("user:s-1", 1))
                .isInstanceOf(RateLimiterBackendException.class)
                .hasMessageContaining("Redis token-bucket script execution failed")
                .hasMessageContaining("user:s-1")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    // ---------- 默认 permits=1 重载(接口 default 方法委托) ----------

    @Test
    @DisplayName("tryAcquire_无permits参数_用默认permits=1")
    void tryAcquire_defaultPermits_passesOneToLua() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, "99.500000", 0L));

        boolean result = bucket.tryAcquire("user:s-2");

        assertThat(result).isTrue();
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        // ARGV[4] = permits = "1"(接口 default 透传)
        assertThat(argsCaptor.getValue()[3]).isEqualTo("1");
    }

    // ---------- 自定义 permits ----------

    @Test
    @DisplayName("tryAcquire_自定义permits大于1_正确传给Lua_返回Decision")
    void tryAcquire_customPermits_passedThrough() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, "95.000000", 0L));

        boolean result = bucket.tryAcquire("user:s-1", 5);

        assertThat(result).isTrue();
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        // ARGV[4] = permits = "5"
        assertThat(argsCaptor.getValue()[3]).isEqualTo("5");
    }

    // ---------- 参数校验 ----------

    @Test
    @DisplayName("tryAcquire_key为null或blank抛IllegalArgumentException_不发Redis")
    void tryAcquire_blankKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> bucket.tryAcquire(null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucketKey must be non-blank");
        assertThatThrownBy(() -> bucket.tryAcquire("", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bucket.tryAcquire("   ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucketKey must be non-blank");
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("tryAcquire_permits为零或负抛IllegalArgumentException_不发Redis")
    void tryAcquire_invalidPermits_throwsIllegalArgument() {
        assertThatThrownBy(() -> bucket.tryAcquire("k1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits must be > 0");
        assertThatThrownBy(() -> bucket.tryAcquire("k1", -3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits must be > 0");
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    // ---------- 构造器校验 ----------

    @Test
    @DisplayName("new_RedisTokenBucket_capacity或refill非法抛IllegalArgumentException")
    void constructor_invalidArgs_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RedisTokenBucket(redis, properties, 0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity must be > 0");
        assertThatThrownBy(() -> new RedisTokenBucket(redis, properties, -1, 10.0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RedisTokenBucket(redis, properties, 100, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refillTokensPerSecond must be > 0");
        assertThatThrownBy(() -> new RedisTokenBucket(redis, properties, 100, -1.0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RedisTokenBucket(null, properties, 100, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis must be non-null");
        assertThatThrownBy(() -> new RedisTokenBucket(redis, null, 100, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("properties must be non-null");
    }

    @Test
    @DisplayName("new_RedisTokenBucket_脚本路径无效抛IllegalStateException_启动失败")
    void constructor_invalidScriptLocation_throwsIllegalState() {
        RateLimitProperties bad = new RateLimitProperties();
        bad.setScriptLocation("nonexistent/");

        assertThatThrownBy(() -> new RedisTokenBucket(redis, bad, 100, 10.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to load Lua script");
    }

    // ---------- 详细决策路径 ----------

    @Test
    @DisplayName("tryAcquireDetailed_Lua返放行_Decision记录allowed和tokensLeft和retryAfter")
    void tryAcquireDetailed_allowed_returnsDecision() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, "99.500000", 0L));

        Decision d = bucket.tryAcquireDetailed("user:s-1", 1);

        assertThat(d.allowed()).isTrue();
        assertThat(d.tokensLeft()).isEqualTo(99.5);
        assertThat(d.retryAfterMs()).isZero();
    }

    @Test
    @DisplayName("tryAcquireDetailed_Lua返拒绝_Decision记录retryAfterMs正确")
    void tryAcquireDetailed_denied_returnsDecision() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, "0.000000", 100L));

        Decision d = bucket.tryAcquireDetailed("user:s-1", 1);

        assertThat(d.allowed()).isFalse();
        assertThat(d.tokensLeft()).isZero();
        assertThat(d.retryAfterMs()).isEqualTo(100L);
    }

    @Test
    @DisplayName("tryAcquireDetailed_Lua返retryAfter为负_收敛到0_防止W2误算")
    void tryAcquireDetailed_negativeRetryAfter_convergesToZero() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, "0.000000", -1L));

        Decision d = bucket.tryAcquireDetailed("user:s-1", 1);

        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterMs()).isZero();
    }

    @Test
    @DisplayName("tryAcquireDetailed_Lua返回malformed_包装为RateLimiterBackendException")
    void tryAcquireDetailed_malformedLuaResult_throwsRateLimiterBackend() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L));

        // parseDecision 抛的 RateLimiterBackendException 被 executeDetailed 的 catch 块
        // 二次包装,所以外层 message 是 "Redis token-bucket script execution failed";
        // 真实 "Malformed token-bucket.lua result" 在 cause.message 里。
        assertThatThrownBy(() -> bucket.tryAcquireDetailed("user:s-1", 1))
                .isInstanceOf(RateLimiterBackendException.class)
                .hasMessageContaining("Redis token-bucket script execution failed")
                .hasCauseInstanceOf(RateLimiterBackendException.class);
    }

    // ---------- 构造后属性可见性 ----------

    @Test
    @DisplayName("capacity_refillTokensPerSecond_getter返回构造时绑定的值")
    void capacityAndRefill_exposedViaAccessors() {
        assertThat(bucket.capacity()).isEqualTo(100);
        assertThat(bucket.refillTokensPerSecond()).isEqualTo(10.0);
    }
}
