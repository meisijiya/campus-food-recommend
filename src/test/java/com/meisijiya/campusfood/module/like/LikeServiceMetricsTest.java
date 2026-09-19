package com.meisijiya.campusfood.module.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.meisijiya.campusfood.common.exception.ApiException;
import com.meisijiya.campusfood.config.MicrometerConfig;
import com.meisijiya.campusfood.config.RabbitMQConfig;

/**
 * {@link LikeService} 业务指标单元测试(F-9 W1)。
 *
 * <p>验证:
 * <ul>
 *   <li>like 首次成功路径 — {@code like_count_total{endpoint="like"}} Counter 自增 1 次</li>
 *   <li>like 60s 幂等命中 — Counter <strong>不</strong> 自增(只数真正落库的)</li>
 *   <li>like 参数校验失败 — Counter 不自增(MQ 也不该投递,保持 idempotency 不变量)</li>
 *   <li>多次 like 不同 (sid, mid) — Counter 自增对应次数</li>
 * </ul>
 *
 * <p>用 {@link SimpleMeterRegistry}(纯 in-memory,无 Prometheus 依赖)直接断言 {@code find(...).count()}
 * — 不依赖 Spring context,纯单测 Surefire 跑。
 *
 * @author meisijiya
 */
class LikeServiceMetricsTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RabbitTemplate rabbit;
    private MeterRegistry meterRegistry;
    private MicrometerConfig micrometerConfig;
    private LikeService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        rabbit = mock(RabbitTemplate.class);
        // F-9 W1:SimpleMeterRegistry 在内存维护 Counter,find().count() 直接断言。
        meterRegistry = new SimpleMeterRegistry();
        micrometerConfig = new MicrometerConfig(meterRegistry);
        when(redis.opsForValue()).thenReturn(ops);
        service = new LikeService(redis, rabbit, micrometerConfig);
    }

    @Test
    @DisplayName("like_首次成功_like_count_total自增1")
    void like_firstSuccess_counterIncrementsByOne() {
        when(ops.setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        double before = counterValue();
        boolean result = service.like("s-1", "m-1");
        double after = counterValue();

        assertThat(result).isTrue();
        assertThat(after - before).isEqualTo(1.0);
        verify(rabbit).convertAndSend(eq(RabbitMQConfig.QUEUE_LIKE_DB_WRITE), any(LikeMessage.class));
    }

    @Test
    @DisplayName("like_60s幂等命中_counter不自增")
    void like_idempotentHit_counterDoesNotIncrement() {
        when(ops.setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        double before = counterValue();
        boolean result = service.like("s-1", "m-1");
        double after = counterValue();

        assertThat(result).isFalse();
        assertThat(after).isEqualTo(before); // 幂等命中不打点
        verify(rabbit, never()).convertAndSend(anyString(), any(LikeMessage.class));
    }

    @Test
    @DisplayName("like_参数校验失败_counter不自增")
    void like_validationFails_counterDoesNotIncrement() {
        double before = counterValue();
        try {
            service.like(null, "m-1");
        } catch (ApiException expected) {
            // 验证抛出的异常符合 contract
            assertThat(expected.getMessage()).contains("studentId must be non-blank");
        }
        double after = counterValue();

        assertThat(after).isEqualTo(before);
        verify(ops, never()).setIfAbsent(any(), any(), any(Duration.class));
        verify(rabbit, never()).convertAndSend(anyString(), any(LikeMessage.class));
    }

    @Test
    @DisplayName("like_多次不同商户_like_count_total累加对应次数")
    void like_multipleDistinct_callsAccumulateCounter() {
        // Mock 都返 true,模拟每个 (sid, mid) 都是首次
        when(ops.setIfAbsent(any(String.class), eq("1"), any(Duration.class)))
                .thenReturn(true);

        double before = counterValue();
        service.like("s-1", "m-1");
        service.like("s-1", "m-2");
        service.like("s-2", "m-3");
        double after = counterValue();

        assertThat(after - before).isEqualTo(3.0);
    }

    @Test
    @DisplayName("MicrometerConfig.likeCounter_helper注册到正确meterName_and_tag")
    void micrometerConfig_likeCounter_publishesCorrectMeterNameAndTag() {
        Counter c = micrometerConfig.likeCounter("like");
        c.increment();
        c.increment();

        assertThat(c.count()).isEqualTo(2.0);
        assertThat(c.getId().getName()).isEqualTo(MicrometerConfig.LIKE_COUNT);
        assertThat(c.getId().getTag("endpoint")).isEqualTo("like");
    }

    /** 提取当前 like_count_total 计数(避免对 tag 字符串硬编码的脆弱性)。 */
    private double counterValue() {
        Counter c = meterRegistry.find(MicrometerConfig.LIKE_COUNT)
                .tag("endpoint", "like")
                .counter();
        return c == null ? 0.0 : c.count();
    }
}
