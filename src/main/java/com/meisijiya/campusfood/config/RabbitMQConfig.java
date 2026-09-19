package com.meisijiya.campusfood.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RabbitMQ 配置(F-5 B-5 bullet — 点赞异步落库 + 死信补偿)。
 *
 * <h2>拓扑</h2>
 * <pre>
 *   ┌────────────────────────┐
 *   │ default direct exchange │  (Spring AMQP 默认)
 *   └──────────┬─────────────┘
 *              │ routing-key = "like.db.write"
 *              ▼
 *   ┌────────────────────────┐
 *   │   queue: like.db.write │  (durable=true, x-dead-letter-exchange=like.db.write.dlx)
 *   └──────────┬─────────────┘
 *              │ on consumer nack / requeue=false (消费者落库失败时手动 nack)
 *              ▼
 *   ┌──────────────────────────────┐
 *   │ DLX: like.db.write.dlx       │  (durable=true)
 *   └──────────┬───────────────────┘
 *              │ routing-key = "like.db.write.dlq"
 *              ▼
 *   ┌────────────────────────┐
 *   │ DLQ: like.db.write.dlq │  (durable=true, 供人工补偿 / 监控告警)
 *   └────────────────────────┘
 * </pre>
 *
 * <h2>关键纪律</h2>
 * <ul>
 *   <li>队列 + DLX + DLQ 全部 {@code durable=true}:容器重启 / RabbitMQ 重启后消息不丢。</li>
 *   <li>主队列声明 {@code x-dead-letter-exchange}:消费者抛 {@code AmqpRejectAndDontRequeueException}
 *       或手动 {@code channel.basicNack(requeue=false)} 时,RabbitMQ 自动路由到 DLX,不再无限重试。</li>
 *   <li>序列化用 {@link Jackson2JsonMessageConverter}:消费者按 Java 类型反序列化 {@link
 *       com.meisijiya.campusfood.module.like.LikeMessage};ObjectMapper 由 Spring Boot 自动注入
 *       (带 JavaTimeModule 已注册),直接复用。</li>
 *   <li>不使用 Spring Boot 自动 {@code RabbitListener} 默认的 {@code SimpleMessageConverter}
 *       (那会把对象用 {@code ObjectOutputStream} 序列化,跨语言不可读)。</li>
 * </ul>
 *
 * @author meisijiya
 */
@Configuration
public class RabbitMQConfig {

    /** 主队列:routing-key 与队列名同构,简化绑定。 */
    public static final String QUEUE_LIKE_DB_WRITE = "like.db.write";

    /** DLX 名。 */
    public static final String EXCHANGE_LIKE_DB_WRITE_DLX = "like.db.write.dlx";

    /** DLQ 名(F-5 实际不做消费,供监控 / 人工补偿)。 */
    public static final String QUEUE_LIKE_DB_WRITE_DLQ = "like.db.write.dlq";

    /** 绑定 routing-key。 */
    public static final String ROUTING_KEY_LIKE_DB_WRITE = "like.db.write";

    /** DLQ 绑定 routing-key。 */
    public static final String ROUTING_KEY_LIKE_DB_WRITE_DLQ = "like.db.write.dlq";

    /**
     * 主队列:durable + 绑定 DLX。
     *
     * <p>注意不设 {@code x-message-ttl}:由消费者按批量触发 flush,不需要消息过期自动淘汰;
     * 也不设 {@code x-max-length}:瓶颈在消费者并发,不在队列长度。
     */
    @Bean
    public Queue likeDbWriteQueue() {
        return QueueBuilder.durable(QUEUE_LIKE_DB_WRITE)
                .withArgument("x-dead-letter-exchange", EXCHANGE_LIKE_DB_WRITE_DLX)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_LIKE_DB_WRITE_DLQ)
                .build();
    }

    /** DLX 直连交换机(durable=true)。 */
    @Bean
    public DirectExchange likeDbWriteDlx() {
        return new DirectExchange(EXCHANGE_LIKE_DB_WRITE_DLX, true, false);
    }

    /** DLQ 队列(durable=true)。 */
    @Bean
    public Queue likeDbWriteDlq() {
        return QueueBuilder.durable(QUEUE_LIKE_DB_WRITE_DLQ).build();
    }

    /**
     * 默认 exchange + 主队列绑定。
     *
     * <p>Spring AMQP 默认有一个名字为 ""(空字符串)的 direct exchange,routing-key 即队列名。
     * 这里用 {@link Binding} 构造器直接绑定,避开 {@code BindingBuilder.bind(...).to(String)} 不存在的 API。
     */
    @Bean
    public Binding likeDbWriteBinding() {
        return new Binding(
                QUEUE_LIKE_DB_WRITE,
                Binding.DestinationType.QUEUE,
                "",
                ROUTING_KEY_LIKE_DB_WRITE,
                null);
    }

    /** DLX -> DLQ 绑定。 */
    @Bean
    public Binding likeDbWriteDlqBinding(Queue likeDbWriteDlq, DirectExchange likeDbWriteDlx) {
        return BindingBuilder.bind(likeDbWriteDlq).to(likeDbWriteDlx).with(ROUTING_KEY_LIKE_DB_WRITE_DLQ);
    }

    /**
     * 消息转换器:Jackson JSON,跨语言 + Java 端零样板。
     *
     * <p>{@code Jackson2JsonMessageConverter} 在 Spring AMQP 2.2+ 推荐显式传 {@code ObjectMapper},
     * 这样能复用 Spring Boot 自动配置的 ObjectMapper(已注册 JavaTimeModule 等)。
     */
    @Bean
    public MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}