package com.meisijiya.campusfood.module.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.meisijiya.campusfood.common.exception.ApiException;
import com.meisijiya.campusfood.config.RabbitMQConfig;

/**
 * {@link LikeService} 单元测试(F-5 B-5 bullet)— 覆盖首次成功 + 60s 幂等 + 参数校验 4 路径。
 *
 * <p>测试纪律:纯 Mockito + AssertJ,不连真实 Redis / RabbitMQ;关键参数(Redis key / TTL /
 * RabbitTemplate 目标 exchange)用 {@link ArgumentCaptor} 抓取断言。
 *
 * <p>不需要 {@code @ExtendWith(MockitoExtension.class)} — 这里手写 {@link MockitoExtension}
 * 用不到的 setUp(直接 {@code mock(...)})即可;沿袭 {@code MerchantHeatCalculatorTest}
 * 的风格。
 *
 * @author meisijiya
 */
class LikeServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RabbitTemplate rabbit;
    private LikeService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        rabbit = mock(RabbitTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new LikeService(redis, rabbit);
    }

    @Test
    @DisplayName("like_首次调用_setIfAbsent返true_投MQ并返回true")
    void like_firstCall_returnsTrue_andSendsMessage() {
        when(ops.setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        boolean result = service.like("s-1", "m-1");

        assertThat(result).isTrue();
        // Redis SETNX key/value/TTL 三参断言
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(ops, times(1)).setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(LikeService.IDEM_TTL);
        // RabbitMQ 投递 — 主队列名一致
        verify(rabbit, times(1)).convertAndSend(eq(RabbitMQConfig.QUEUE_LIKE_DB_WRITE), any(LikeMessage.class));
    }

    @Test
    @DisplayName("like_幂等命中_setIfAbsent返false_不投MQ_返回false")
    void like_idempotent_returnsFalse_andDoesNotSend() {
        when(ops.setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        boolean result = service.like("s-1", "m-1");

        assertThat(result).isFalse();
        verify(ops, times(1)).setIfAbsent(eq("like:idem:s-1:m-1"), eq("1"), any(Duration.class));
        verify(rabbit, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    @DisplayName("like_setIfAbsent返null_视为失败_不投MQ_返回false")
    void like_setIfAbsentReturnsNull_treatedAsFailure() {
        // 极端 case:Redis 返回 null(网络断开 / 异常分支) — 视为非首次,不投 MQ
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(null);

        boolean result = service.like("s-1", "m-1");

        assertThat(result).isFalse();
        verifyNoInteractions(rabbit);
    }

    @Test
    @DisplayName("like_studentId为null_抛ApiException_BAD_REQUEST")
    void like_studentIdNull_throwsApiException() {
        assertThatThrownBy(() -> service.like(null, "m-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("studentId must be non-blank");
        verify(ops, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(rabbit);
    }

    @Test
    @DisplayName("like_studentId为blank_抛ApiException_BAD_REQUEST")
    void like_studentIdBlank_throwsApiException() {
        assertThatThrownBy(() -> service.like("   ", "m-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("studentId must be non-blank");
        verifyNoInteractions(ops, rabbit);
    }

    @Test
    @DisplayName("like_studentId超长_抛ApiException_BAD_REQUEST")
    void like_studentIdTooLong_throwsApiException() {
        assertThatThrownBy(() -> service.like("a".repeat(65), "m-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("studentId length must be <= 64");
        verifyNoInteractions(ops, rabbit);
    }

    @Test
    @DisplayName("like_merchantId为null_抛ApiException_BAD_REQUEST")
    void like_merchantIdNull_throwsApiException() {
        assertThatThrownBy(() -> service.like("s-1", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("merchantId must be non-blank");
        verifyNoInteractions(ops, rabbit);
    }

    @Test
    @DisplayName("like_merchantId为blank_抛ApiException_BAD_REQUEST")
    void like_merchantIdBlank_throwsApiException() {
        assertThatThrownBy(() -> service.like("s-1", ""))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("merchantId must be non-blank");
        verifyNoInteractions(ops, rabbit);
    }

    @Test
    @DisplayName("like_merchantId超长_抛ApiException_BAD_REQUEST")
    void like_merchantIdTooLong_throwsApiException() {
        assertThatThrownBy(() -> service.like("s-1", "a".repeat(65)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("merchantId length must be <= 64");
        verifyNoInteractions(ops, rabbit);
    }

    @Test
    @DisplayName("like_merchantId含冒号_字符白名单拒绝_防止Redis key空间污染")
    void like_merchantIdWithIllegalChars_throwsApiException() {
        // Security verifier finding #1:merchantId 是路径变量,客户端可发任意字符,
        // 允许 ':' '*' '\r\n' 等可让 key 形式化被解析歧义(键空间冲突 + cluster slot 错配)
        assertThatThrownBy(() -> service.like("s-1", "m:bogus"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("contains illegal chars");
        verifyNoInteractions(ops, rabbit);

        assertThatThrownBy(() -> service.like("s-1", "m bogus"))      // 空格
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("contains illegal chars");

        assertThatThrownBy(() -> service.like("s-1", "m\r\nbogus"))   // CRLF
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("contains illegal chars");
    }

    @Test
    @DisplayName("like_studentId含冒号_字符白名单拒绝")
    void like_studentIdWithIllegalChars_throwsApiException() {
        // studentId 来自 JWT sub(可控性低),但仍走同一校验,因为 SETNX key 拼接同样会污染
        assertThatThrownBy(() -> service.like("s:1", "m-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("contains illegal chars");
        verifyNoInteractions(ops, rabbit);
    }
}