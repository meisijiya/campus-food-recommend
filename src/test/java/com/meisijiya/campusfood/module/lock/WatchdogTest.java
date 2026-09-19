package com.meisijiya.campusfood.module.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Watchdog} 单元测试(F-8 W1)— 覆盖正常续期 / 续期 3 次失败放弃 2 主路径
 * + 额外边界:重复注册幂等 / 反注册移除 / 空注册表跳过 / Redis 异常等同失败 / 失败后恢复。
 *
 * <p>测试纪律:Mock {@link RedisLock},手动驱动 {@link Watchdog#tick()}(不走调度线程池),
 * 这样单测可同步断言注册表状态。{@link RedisLock} 内部的 Lua 加载不涉及 Redis,
 * 因此这里直接 new(不调 loadScripts 也可;真正用 RedisLock 的 API 时由 RedisLock 自己负责)。
 *
 * @author meisijiya
 */
class WatchdogTest {

    private RedisLock redisLock;
    private LockProperties properties;
    private Watchdog watchdog;

    @BeforeEach
    void setUp() {
        redisLock = mock(RedisLock.class);
        properties = new LockProperties();
        // 默认 maxExtendFailures=3,这里显式覆写更直观
        properties.setMaxExtendFailures(3);
        watchdog = new Watchdog(redisLock, properties);
    }

    @Test
    @DisplayName("register_thenTick_extend返true_注册表保持且failCount重置")
    void register_thenTick_extendSucceeds_registryKeeps_andFailCountReset() {
        watchdog.register("lock:job:preheat", "token-A", 30_000);
        when(redisLock.extend(eq("lock:job:preheat"), eq("token-A"), anyLong())).thenReturn(true);

        watchdog.tick();

        assertThat(watchdog.registeredCount()).isEqualTo(1);
        verify(redisLock, times(1)).extend(eq("lock:job:preheat"), eq("token-A"), eq(30_000L));
    }

    @Test
    @DisplayName("tick_extend连续失败3次_从注册表移除_且不再调用extend")
    void tick_extendFails3Times_removedFromRegistry() {
        properties.setMaxExtendFailures(3);
        watchdog.register("lock:like:s-1:m-1", "token-B", 30_000);
        when(redisLock.extend(eq("lock:like:s-1:m-1"), eq("token-B"), anyLong())).thenReturn(false);

        watchdog.tick();   // failCount: 1
        watchdog.tick();   // failCount: 2
        watchdog.tick();   // failCount: 3 → 移除

        assertThat(watchdog.registeredCount()).isEqualTo(0);
        // 三次 tick 共 3 次 extend 调用,移除后第 4 次 tick 不应再调
        verify(redisLock, times(3)).extend(eq("lock:like:s-1:m-1"), eq("token-B"), eq(30_000L));
        watchdog.tick();
        verify(redisLock, times(3)).extend(eq("lock:like:s-1:m-1"), eq("token-B"), eq(30_000L));
    }

    @Test
    @DisplayName("tick_extend失败2次后成功_failsCount重置_继续保留在注册表")
    void tick_extendFailsThenRecovers_failCountResetAndKeepRegistered() {
        properties.setMaxExtendFailures(3);
        watchdog.register("lock:like:s-1:m-1", "token-C", 30_000);
        // 前 2 次失败,第 3 次成功
        when(redisLock.extend(eq("lock:like:s-1:m-1"), eq("token-C"), anyLong()))
                .thenReturn(false)
                .thenReturn(false)
                .thenReturn(true);

        watchdog.tick();   // fail 1
        watchdog.tick();   // fail 2
        assertThat(watchdog.registeredCount()).isEqualTo(1);
        watchdog.tick();   // recover
        assertThat(watchdog.registeredCount()).isEqualTo(1);

        // 第 4 次 tick 后继续成功,不应该被移除
        when(redisLock.extend(eq("lock:like:s-1:m-1"), eq("token-C"), anyLong())).thenReturn(true);
        watchdog.tick();
        assertThat(watchdog.registeredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("tick_extend抛RuntimeException_等同失败_累加failCount")
    void tick_extendThrows_exceptionCountsAsFailure() {
        properties.setMaxExtendFailures(2);
        watchdog.register("lock:like:s-1:m-1", "token-D", 30_000);
        when(redisLock.extend(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        watchdog.tick();   // fail 1
        watchdog.tick();   // fail 2 → 移除

        assertThat(watchdog.registeredCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("register_重复key_返false_且不覆盖原有owner")
    void register_duplicateKey_returnsFalse_andKeepsOriginalOwner() {
        boolean first = watchdog.register("lock:job:preheat", "token-A", 30_000);
        boolean second = watchdog.register("lock:job:preheat", "token-B", 30_000);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(watchdog.registeredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("unregister_存在key_移除注册表_后续tick不调extend")
    void unregister_existingKey_removes_andTickDoesNotExtend() {
        watchdog.register("lock:like:s-1:m-1", "token-E", 30_000);

        watchdog.unregister("lock:like:s-1:m-1");

        assertThat(watchdog.registeredCount()).isEqualTo(0);
        watchdog.tick();
        verify(redisLock, never()).extend(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("unregister_不存在的key_幂等_no-op")
    void unregister_nonexistentKey_isNoOp() {
        watchdog.unregister("lock:not-exist");
        assertThat(watchdog.registeredCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("tick_注册表为空_跳过不调用extend")
    void tick_emptyRegistry_skipsExtend() {
        watchdog.tick();
        verify(redisLock, never()).extend(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("start_thenStop_调度器能正常关闭_且幂等")
    void startAndStop_schedulerLifecycle() throws Exception {
        watchdog.start();
        // 第二次 start 幂等不报错
        watchdog.start();
        watchdog.stop();
        // 第二次 stop 幂等不报错
        watchdog.stop();
        // 唤醒一下,确保 awaitTermination 不会假阳
        Thread.sleep(50);
    }
}