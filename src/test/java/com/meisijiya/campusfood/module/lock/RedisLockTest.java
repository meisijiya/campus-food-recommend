package com.meisijiya.campusfood.module.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link RedisLock} 单元测试(F-8 W1)— 覆盖 6 路径:
 * tryLock 成功 / 已存在拒绝 / 释放成功 / 释放别人的锁返 0 / extend 成功 / extend 别人的锁返 0。
 *
 * <p>测试纪律:Mock {@link StringRedisTemplate},不连真实 Redis;
 * 关键参数(RedisScript 类型 / keys / args)用 {@link ArgumentCaptor} 抓取断言,
 * 避免脆弱的字符串匹配。{@link RedisLock#loadScripts} 在 setUp 阶段跑(只读 classpath
 * 资源),保证单测可以驱动后续 API。
 *
 * @author meisijiya
 */
class RedisLockTest {

    private StringRedisTemplate redis;
    private RedisLock lock;
    private LockProperties properties;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        properties = new LockProperties();
        lock = new RedisLock(redis, properties);
        lock.loadScripts();
    }

    @Test
    @DisplayName("tryLock_脚本返1_返回true_且ownerToken作为ARGV[1]传入")
    void tryLock_scriptReturnsOne_returnsTrue_andPassesOwnerToken() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean acquired = lock.tryLock("lock:like:s-1:m-1", "token-A", 30);

        assertThat(acquired).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(redis).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly("lock:like:s-1:m-1");
        // args 顺序:ARGV[1]=ownerToken, ARGV[2]=ttlSec
        assertThat(argsCaptor.getValue()).containsExactly("token-A", "30");
    }

    @Test
    @DisplayName("tryLock_脚本返0_返回false_表示key已存在")
    void tryLock_scriptReturnsZero_returnsFalse() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        boolean acquired = lock.tryLock("lock:job:preheat", "token-B", 30);

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("tryLock_脚本返null_视为失败_返回false")
    void tryLock_scriptReturnsNull_returnsFalse() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);

        boolean acquired = lock.tryLock("lock:job:preheat", "token-B", 30);

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("release_脚本返1_返回true_且仅传ARGV[1]=ownerToken")
    void release_scriptReturnsOne_returnsTrue_andOnlyOwnerToken() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean released = lock.release("lock:like:s-1:m-1", "token-A");

        assertThat(released).isTrue();
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(redis).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsExactly("token-A");
    }

    @Test
    @DisplayName("release_脚本返0_返回false_防误删别人的锁")
    void release_scriptReturnsZero_returnsFalse_doesNotDeleteOthersLock() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        boolean released = lock.release("lock:like:s-1:m-1", "token-WRONG");

        assertThat(released).isFalse();
    }

    @Test
    @DisplayName("extend_脚本返1_返回true_且传ARGV[1]=ownerToken_ARGV[2]=ttlMs")
    void extend_scriptReturnsOne_returnsTrue_andPassesTtlMs() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean extended = lock.extend("lock:like:s-1:m-1", "token-A", 30_000);

        assertThat(extended).isTrue();
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(redis).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsExactly("token-A", "30000");
    }

    @Test
    @DisplayName("extend_脚本返0_返回false_不续期别人的锁")
    void extend_scriptReturnsZero_returnsFalse_doesNotExtendOthersLock() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        boolean extended = lock.extend("lock:like:s-1:m-1", "token-WRONG", 30_000);

        assertThat(extended).isFalse();
    }

    @Test
    @DisplayName("tryLock_ttlSec小于等于0_抛IllegalArgumentException")
    void tryLock_nonPositiveTtl_throwsIllegalArgument() {
        assertThatThrownBy(() -> lock.tryLock("k", "t", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttlSec must be > 0");
        assertThatThrownBy(() -> lock.tryLock("k", "t", -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("extend_ttlMs小于等于0_抛IllegalArgumentException")
    void extend_nonPositiveTtl_throwsIllegalArgument() {
        assertThatThrownBy(() -> lock.extend("k", "t", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttlMs must be > 0");
        assertThatThrownBy(() -> lock.extend("k", "t", -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tryLock_Redis抛RuntimeException_包装成IllegalStateException")
    void tryLock_redisThrows_wrappedAsIllegalState() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> lock.tryLock("k", "t", 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis lock script execution failed")
                .hasMessageContaining("k");
    }

    @Test
    @DisplayName("loadScripts_脚本缺失_抛IllegalStateException_启动失败")
    void loadScripts_missingScript_throwsIllegalState() {
        LockProperties bad = new LockProperties();
        bad.setScriptLocation("nonexistent/");
        RedisLock lock2 = new RedisLock(redis, bad);

        assertThatThrownBy(lock2::loadScripts)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to load Lua script");
    }
}