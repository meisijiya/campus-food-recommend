package com.meisijiya.campusfood.module.like;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.meisijiya.campusfood.common.exception.ApiException;
import com.meisijiya.campusfood.config.RabbitMQConfig;

/**
 * 点赞业务服务(F-5 B-5 bullet)— 幂等点赞 + 异步落库编排。
 *
 * <h2>主流程</h2>
 * <ol>
 *   <li>Redis 前置拦截:{@code SET like:idem:<sid>:<mid> 1 NX EX 60} — 60s 内重复请求 SETNX 失败,直接
 *       返回 {@code false},不再投递 MQ,避免消息堆积。</li>
 *   <li>SETNX 成功 → 投 {@link RabbitMQConfig#QUEUE_LIKE_DB_WRITE} 队列,载荷是 {@link LikeMessage},
 *       由 {@link LikeMessageConsumer} 批量落库。</li>
 * </ol>
 *
 * <h2>关键纪律</h2>
 * <ul>
 *   <li>幂等 TTL 选 60s:CARROT bullet spec 5.1 + CONTEXT §6 都明确 60s;语义对齐 SPEC "60 秒内重复只生效一次"。</li>
 *   <li>{@code setIfAbsent(key, "1", Duration)} 走 Redis {@code SET ... NX EX <seconds>},
 *       O(1) + 原子;Spring Data Redis 已封好。</li>
 *   <li>不引入二级校验(查询 likes 表):Redis 是事实来源 — 性能 + 一致性足够,
 *       MySQL 主键校验留给消费者落库时的主键冲突兜底。</li>
 *   <li>不带 Lombok,字段全包私有 + 显式构造器注入(F-1 起的项目惯例)。</li>
 * </ul>
 *
 * @author meisijiya
 */
@Service
public class LikeService {

    private static final Logger log = LoggerFactory.getLogger(LikeService.class);

    /** 幂等 key 前缀(完整 key = {@code PREFIX + studentId + ":" + merchantId})。 */
    public static final String IDEM_KEY_PREFIX = "like:idem:";

    /** 幂等 TTL:60 秒 — 对齐 CONTEXT §6 / spec B-5 5.1。 */
    public static final Duration IDEM_TTL = Duration.ofSeconds(60);

    /**
     * id 字符白名单 — 与 {@code RedisShardedWriter.KEY_SAFE} 保持一致([A-Za-z0-9_.-]+),
     * 防止 {@code studentId / merchantId} 含 {@code :} {@code *} {@code \r\n} 等造成 Redis
     * key 空间污染或 cluster slot 错配。studentId 来自 JWT sub(可控性低),merchantId 是路径
     * 变量(客户端可发任意字符)是主要防御对象。Lettuce / Jedis 协议层本身防 RESP 帧注入,
     * 本校验是 cache pollution → DoS 的前置屏障。
     */
    static final Pattern ID_SAFE = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;

    public LikeService(StringRedisTemplate redis, RabbitTemplate rabbit) {
        this.redis = redis;
        this.rabbit = rabbit;
    }

    /**
     * 主入口:点赞。60 秒内重复请求返回 {@code false},首次成功返回 {@code true}。
     *
     * <p>前置校验失败抛 {@link ApiException}(由 {@code GlobalExceptionHandler} 转 400);
     * 幂等命中返 {@code false};真正发消息后返 {@code true}。
     *
     * @param studentId  学生 ID(JWT sub)
     * @param merchantId 被点赞的商户 ID
     * @return {@code true} = 首次成功(已投递 MQ);{@code false} = 60s 内重复点赞
     * @throws ApiException 400:studentId / merchantId 为空或超长
     */
    public boolean like(String studentId, String merchantId) {
        validateId("studentId", studentId, 64);
        validateId("merchantId", merchantId, 64);

        String key = IDEM_KEY_PREFIX + studentId + ":" + merchantId;
        // SET key "1" NX EX 60 — 原子;返回 true 表示 set 成功(首次),false 表示 key 已存在(重复)
        Boolean firstTime = redis.opsForValue().setIfAbsent(key, "1", IDEM_TTL);
        boolean acquired = Boolean.TRUE.equals(firstTime);
        if (!acquired) {
            log.debug("LikeService idempotent hit (60s window), studentId={} merchantId={}", studentId, merchantId);
            return false;
        }

        // 首次 → 投递 MQ
        LikeMessage message = new LikeMessage(studentId, merchantId, Instant.now());
        rabbit.convertAndSend(RabbitMQConfig.QUEUE_LIKE_DB_WRITE, message);
        log.info("LikeService liked studentId={} merchantId={} createdAt={}", studentId, merchantId, message.createdAt());
        return true;
    }

    /**
     * 参数校验:非空 + 长度上限 + 字符白名单 — 与 {@code RedisShardedWriter.KEY_SAFE} 同款纪律。
     * 防止 studentId/merchantId 拼接出畸形 Redis key(空格 / 冒号 / 星号 / CR LF 等)。
     *
     * @throws ApiException 400 BAD_REQUEST
     */
    private static void validateId(String name, String value, int maxLen) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, name + " must be non-blank");
        }
        if (value.length() > maxLen) {
            throw new ApiException(HttpStatus.BAD_REQUEST, name + " length must be <= " + maxLen);
        }
        if (!ID_SAFE.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    name + " contains illegal chars (allowed: [A-Za-z0-9_.-])");
        }
    }
}