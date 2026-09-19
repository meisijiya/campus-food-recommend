package com.meisijiya.campusfood.module.like;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.meisijiya.campusfood.config.RabbitMQConfig;

/**
 * {@link LikeService} 集成测试(F-5 B-5 bullet acceptance #11)— Testcontainers 起 Redis + RabbitMQ,
 * 验证点赞幂等链路(Redis SETNX)与异步落库消息投递(默认 exchange + 主队列 {@code like.db.write}),
 * 并验证 {@link RabbitMQConfig#QUEUE_LIKE_DB_WRITE_DLQ} 死信补偿链路在反序列化失败时被自动触发。
 *
 * <h2>测试策略</h2>
 * <ul>
 *   <li>{@code @Container GenericContainer<"redis:7.4-alpine">} — Spring Data Redis 直连容器</li>
 *   <li>{@code @Container GenericContainer<"rabbitmq:3.13-management-alpine">} — Spring AMQP 直连容器</li>
 *   <li>{@code @DynamicPropertySource} 把容器端口注入 Spring 配置</li>
 *   <li>主链路(测试 1/2)用 {@code spring.rabbitmq.listener.simple.auto-startup=false} 关掉
 *       {@code LikeMessageConsumer} 容器,这样 {@code like()} 投递的消息会留在主队列等待断言,
 *       不会被消费者抢先吞掉</li>
 *   <li>DLQ 链路(测试 3)在用例内通过 {@link RabbitListenerEndpointRegistry} 临时启动消费者,
 *       让 {@code ConditionalRejectingErrorHandler} 触发 {@code x-dead-letter-exchange} 路由</li>
 *   <li>{@code rabbitTemplate.receive(queue, timeoutMillis)} 直接抓消息断言 — 短轮询避免长时间挂起</li>
 * </ul>
 *
 * <h2>为什么不开 MySQL 容器</h2>
 * <p>Like 链路核心是 Redis 幂等 + MQ 异步落库,DB 只在消费者侧被动写入;H2 in-memory 足够支撑 {@code likes} 表
 * 的 schema 与 JPA 映射验证。如果未来要在 IT 层断言落库结果,再单独开 MySQL 容器。
 *
 * <p>不依赖 F-已起的 docker compose(Testcontainers 自起容器,端口不冲突);本地需 Docker daemon 运行。
 *
 * @author meisijiya
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // dev profile 用 H2 in-memory (MODE=MySQL),关 Hibernate JDBC metadata access 与 MySQL 方言无冲突
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        // dev 默认禁 health indicator;容器可达,重新启用
        "management.health.redis.enabled=true",
        "management.health.rabbit.enabled=true",
        // 类级别关掉消费者,确保主链路测试 1/2 的 receive() 能抓到原始消息;DLQ 测试 3 在用例内手动启动
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Testcontainers
class LikeServiceIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> RABBIT = new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withExposedPorts(5672)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672).toString());
        // RabbitMQ 默认 guest:guest 只能在 localhost,用容器映射端口直接连,凭据保持默认
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    @Autowired
    private LikeService likeService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /** {@code receive(timeoutMillis)} 的短等待时长,够 MQ 推送一圈。 */
    private static final long RECEIVE_TIMEOUT_MS = 500L;

    // ---------- acceptance #11 主链路:首次点赞 → SETNX 命中 → 投 MQ ----------

    @Test
    @DisplayName("like_firstCall_幂等key写入Redis且消息投递like.db.write_且返回true")
    void like_firstCall_setsIdempotencyKey_andSendsMessage() {
        // given — 干净的初始状态;用唯一 merchantId 隔离跨用例串扰
        String studentId = "demo-stu-1";
        String merchantId = "M-FRESH-" + System.nanoTime();
        String idemKey = LikeService.IDEM_KEY_PREFIX + studentId + ":" + merchantId;

        // 先清空目标 key(防止跨用例串扰)
        redis.delete(idemKey);
        // 先把队列里的旧消息排空(消费者关闭状态下可能残留)
        drainQueue(RabbitMQConfig.QUEUE_LIKE_DB_WRITE);
        drainQueue(RabbitMQConfig.QUEUE_LIKE_DB_WRITE_DLQ);

        // when
        boolean liked = likeService.like(studentId, merchantId);

        // then — 返回 true
        assertThat(liked).as("first call should return true (liked)").isTrue();

        // then — Redis 幂等 key 存在,TTL ≈ 60s
        assertThat(redis.hasKey(idemKey))
                .as("idempotency key must be set in Redis after first call").isTrue();
        Long ttl = redis.getExpire(idemKey);
        assertThat(ttl).as("idempotency TTL should be ~60s").isBetween(55L, 60L);

        // then — 主队列有 1 条消息,载荷是 LikeMessage
        // 消费者已关闭(类级别 auto-startup=false),消息不会被抢先吞掉
        Message message = rabbitTemplate.receive(RabbitMQConfig.QUEUE_LIKE_DB_WRITE, RECEIVE_TIMEOUT_MS);
        assertThat(message)
                .as("first call must publish 1 message to like.db.write (listener off → message stays)")
                .isNotNull();
        // 消息载荷含 studentId / merchantId(Jackson2JsonMessageConverter 编码)
        String body = new String(message.getBody());
        assertThat(body).contains(studentId).contains(merchantId);

        // 再 receive 一次,确认只有 1 条消息(消费者关闭,不会重复投递)
        Message secondPeek = rabbitTemplate.receive(RabbitMQConfig.QUEUE_LIKE_DB_WRITE, RECEIVE_TIMEOUT_MS);
        assertThat(secondPeek)
                .as("after one like() call, queue should contain exactly 1 message").isNull();
    }

    // ---------- acceptance #11 副链路:60s 内重复点赞 → 幂等命中 → 不投 MQ ----------

    @Test
    @DisplayName("like_60sIdempotent_第二次调用返false_队列无新消息")
    void like_60sIdempotent_secondCallReturnsFalse_noNewMessage() {
        // given — 唯一 student/merchant 隔离跨用例
        String studentId = "demo-stu-2";
        String merchantId = "M-IDEM-" + System.nanoTime();
        String idemKey = LikeService.IDEM_KEY_PREFIX + studentId + ":" + merchantId;

        redis.delete(idemKey);
        drainQueue(RabbitMQConfig.QUEUE_LIKE_DB_WRITE);
        drainQueue(RabbitMQConfig.QUEUE_LIKE_DB_WRITE_DLQ);

        // 第一次 — 返 true
        boolean first = likeService.like(studentId, merchantId);
        assertThat(first).as("first call within window must return true").isTrue();
        // 把可能投递的消息先排空,确保第二次调用前的队列计数清零
        drainQueue(RabbitMQConfig.QUEUE_LIKE_DB_WRITE);

        // when — 立即第二次
        boolean second = likeService.like(studentId, merchantId);

        // then — 第二次返 false(60s 窗口内幂等命中)
        assertThat(second).as("second call within 60s must return false (idempotent)").isFalse();
        // 幂等 key 仍存在
        assertThat(redis.hasKey(idemKey)).isTrue();

        // then — 队列没有新消息(消费者关闭,任何投递都应当来自 like() 调用)
        Message msgAfter = rabbitTemplate.receive(RabbitMQConfig.QUEUE_LIKE_DB_WRITE, RECEIVE_TIMEOUT_MS);
        assertThat(msgAfter)
                .as("60s idempotent hit must NOT publish a new message to like.db.write").isNull();
    }

    // ---------- acceptance #11 副链路:反序列化失败 → DLQ 兜底 ----------

    @Test
    @DisplayName("like_messageInDlq_反序列化失败的消息自动转投like.db.write.dlq")
    void like_messageInDlq_whenConsumerFailsToPersist() {
        // given — 把整条队列排空,确保 receive 计数精确
        drainQueue(RabbitMQConfig.QUEUE_LIKE_DB_WRITE);
        drainQueue(RabbitMQConfig.QUEUE_LIKE_DB_WRITE_DLQ);

        // 启动所有 @RabbitListener 容器(类级别 auto-startup=false,这里临时启动)
        listenerRegistry.getListenerContainers().forEach(c -> c.start());

        try {
            // when — 绕过 LikeService.like() (它有参数校验,挡掉),直接往主队列发一个 Jackson
            // 无法反序列化为 LikeMessage 的非法 JSON。Spring AMQP 的 MessageListener 用
            // Jackson2JsonMessageConverter 反序列化失败时会抛 AmqpRejectAndDontRequeueException,
            // 队列声明里 x-dead-letter-exchange=like.db.write.dlx 自动路由到 DLQ。
            String malformedJson = "{\"this_is_not_a_LikeMessage\":true}";
            rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE_LIKE_DB_WRITE, malformedJson);

            // then — DLQ 上应当收到这条消息(主队列声明了 x-dead-letter-exchange + x-dead-letter-routing-key)
            Message dlqMessage = rabbitTemplate.receive(RabbitMQConfig.QUEUE_LIKE_DB_WRITE_DLQ, RECEIVE_TIMEOUT_MS);
            assertThat(dlqMessage)
                    .as("malformed message must be routed to DLQ via x-dead-letter-exchange").isNotNull();
            // 载荷与原 malformed JSON 一致(原样转投,不重新序列化)
            String dlqBody = new String(dlqMessage.getBody());
            assertThat(dlqBody).contains("this_is_not_a_LikeMessage");

            // then — 主队列已无该消息(被 reject 不重投)
            Message mainLeftover = rabbitTemplate.receive(RabbitMQConfig.QUEUE_LIKE_DB_WRITE, RECEIVE_TIMEOUT_MS);
            assertThat(mainLeftover)
                    .as("rejected message must NOT remain on the main queue (requeue=false)").isNull();
        } finally {
            // 关闭消费者,避免影响后续 IT 类(本类内已无更多用例,但养成资源清理习惯)
            listenerRegistry.getListenerContainers().forEach(c -> c.stop());
        }
    }

    // ---------- helpers ----------

    /**
     * 把指定队列里的剩余消息全部 drain 掉,用于测试间隔离。
     * 用循环 + receive 直到拿空,避免被异步消费者抢先拿走导致跨用例串扰计数偏差。
     */
    private void drainQueue(String queueName) {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(RECEIVE_TIMEOUT_MS * 2).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            Message msg = rabbitTemplate.receive(queueName, 50L);
            if (msg == null) {
                return;
            }
        }
    }
}