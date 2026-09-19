package com.meisijiya.campusfood.module.like;

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

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.meisijiya.campusfood.config.MicrometerConfig;
import com.meisijiya.campusfood.config.RabbitMQConfig;
import com.meisijiya.campusfood.module.lock.RedisLock;

/**
 * {@link LikeService} F-8 分布式锁集成测试 — 覆盖多实例下 tryLock 竞争失败的点赞路径。
 *
 * <p>策略:Mockito mock 所有依赖;专门覆盖锁的三种状态 — 成功获取(默认)/ 锁竞争失败
 * (tryLock=false)/ 释放失败(release=false)。验证:
 * <ul>
 *   <li>tryLock 失败时 like() 立即返 false,不调 setIfAbsent / convertAndSend / Counter.increment。</li>
 *   <li>tryLock 成功路径走完整 F-5 既有幂等逻辑(F-9 Counter 在锁内执行,锁失败必不增)。</li>
 *   <li>finally 中都尝试 release(无论 tryLock 结果)— lock key 与 TTL 与规格一致。</li>
 * </ul>
 *
 * <p>本测试与 {@link LikeServiceTest} 互补:后者专注 F-5 幂等 + F-9 Counter(setUp 默认
 * tryLock=true),本测试专注 F-8 锁竞争路径。
 *
 * @author meisijiya
 */
class LikeServiceLockTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RabbitTemplate rabbit;
    private MicrometerConfig micrometerConfig;
    private RedisLock redisLock;
    private LikeService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        rabbit = mock(RabbitTemplate.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        micrometerConfig = new MicrometerConfig(meterRegistry);
        redisLock = mock(RedisLock.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new LikeService(redis, rabbit, micrometerConfig, redisLock);
    }

    @Test
    @DisplayName("like_锁竞争失败_返false_不调setIfAbsent_不投MQ_Counter不增")
    void like_lockContended_returnsFalse_skipsAllDownstream() {
        // given — 模拟"另一实例正持有锁"
        when(redisLock.tryLock(anyString(), anyString(), anyLong())).thenReturn(false);
        when(redisLock.release(anyString(), anyString())).thenReturn(false);

        // when
        boolean result = service.like("s-1", "m-1");

        // then
        assertThat(result).isFalse();
        // 关键:锁失败路径必须提前 return false,绝不进入既有 setIfAbsent / MQ / Counter
        verify(ops, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(rabbit, never()).convertAndSend(anyString(), (Object) any());
        // F-9 Counter 也不增
        assertThat(micrometerConfig.likeCounter("like").count()).isZero();
        // finally 仍尝试 release(虽未持有)— 验证锁 key 与 TTL 与规格一致
        verify(redisLock, times(1)).tryLock(
                eq("lock:like:s-1:m-1"), anyString(), eq(LikeService.LOCK_TTL_SEC));
        // 锁未获取成功 → 不能 release(语义:"只释放自己持有的锁")
        verify(redisLock, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("like_锁获取成功且幂等命中_返false_不投MQ_Counter不增_释放")
    void like_lockAcquired_butIdempotentHit_returnsFalse() {
        // given — 锁可用,但 SETNX 失败(60s 内重复)
        when(redisLock.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(redisLock.release(anyString(), anyString())).thenReturn(true);
        when(ops.setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        // when
        boolean result = service.like("s-1", "m-1");

        // then
        assertThat(result).isFalse();
        verify(ops, times(1)).setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class));
        verify(rabbit, never()).convertAndSend(anyString(), (Object) any());
        // 幂等命中 — Counter 不增
        assertThat(micrometerConfig.likeCounter("like").count()).isZero();
        // finally release
        verify(redisLock, times(1)).release(eq("lock:like:s-1:m-1"), anyString());
    }

    @Test
    @DisplayName("like_锁获取成功且首次成功_返true_投MQ_Counter增_释放")
    void like_lockAcquired_andFirstSuccess_returnsTrue() {
        // given
        when(redisLock.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(redisLock.release(anyString(), anyString())).thenReturn(true);
        when(ops.setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        // when
        boolean result = service.like("s-1", "m-1");

        // then
        assertThat(result).isTrue();
        verify(ops, times(1)).setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class));
        verify(rabbit, times(1)).convertAndSend(eq(RabbitMQConfig.QUEUE_LIKE_DB_WRITE), any(LikeMessage.class));
        // 首次成功 — Counter 增 1
        assertThat(micrometerConfig.likeCounter("like").count()).isOne();
        // finally release
        verify(redisLock, times(1)).release(eq("lock:like:s-1:m-1"), anyString());
    }

    @Test
    @DisplayName("like_锁释放失败_不影响返回true_业务结果优先")
    void like_lockAcquired_releaseFails_stillReturnsTrue() {
        // given — 业务首次成功,但释放返回 false(锁已过期被另一实例接管等极端情况)
        when(redisLock.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(redisLock.release(anyString(), anyString())).thenReturn(false);
        when(ops.setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        // when
        boolean result = service.like("s-1", "m-1");

        // then — 业务视角:点赞已投 MQ,Counter 已增,返回值 true;释放失败不影响业务结果
        assertThat(result).isTrue();
        verify(rabbit, times(1)).convertAndSend(eq(RabbitMQConfig.QUEUE_LIKE_DB_WRITE), any(LikeMessage.class));
        verify(redisLock, times(1)).release(eq("lock:like:s-1:m-1"), anyString());
    }

    @Test
    @DisplayName("LOCK_KEY_PREFIX与LOCK_TTL_SEC_符合工单规格")
    void lockConstants_matchSpec() {
        // F-8 工单 §What to build:锁 key 复用 IDEM_KEY_PREFIX 后缀改为 "lock:like:<sid>:<mid>",
        // TTL 5s(Fast path 优化)
        assertThat(LikeService.LOCK_KEY_PREFIX).isEqualTo("lock:like:");
        assertThat(LikeService.LOCK_TTL_SEC).isEqualTo(5L);
        // 锁 key 完整后缀与 IDEM_KEY_PREFIX 后缀一致(仅前缀不同)
        assertThat("lock:like:s-1:m-1").isEqualTo(
                LikeService.LOCK_KEY_PREFIX + "s-1" + ":" + "m-1");
    }
}