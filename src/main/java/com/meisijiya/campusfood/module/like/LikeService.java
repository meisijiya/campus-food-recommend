package com.meisijiya.campusfood.module.like;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.meisijiya.campusfood.common.exception.ApiException;
import com.meisijiya.campusfood.config.MicrometerConfig;
import com.meisijiya.campusfood.config.RabbitMQConfig;
import com.meisijiya.campusfood.module.featureflag.FeatureFlag;
import com.meisijiya.campusfood.module.lock.RedisLock;

/**
 * 点赞业务服务(F-5 B-5 bullet + F-9 业务埋点 + F-8 W2 锁升级 + F-11 W3 feature flag 接入)— 幂等点赞 + 异步落库编排
 * + 业务 Counter + 多实例分布式锁严格一致性 + Feature Flag 接入示范。
 *
 * <h2>主流程</h2>
 * <ol>
 *   <li><b>F-8 分布式锁</b>:{@code tryLock("lock:like:<sid>:<mid>", token, 5s)} — 多实例下保证
 *       同一对 (studentId, merchantId) 在同一时刻只有一个 JVM 进入点赞路径。锁 TTL 5 秒短窗口
 *       (Fast path 优化):点赞本身只需 Redis NX + MQ 投递,远低于 5s;锁竞争失败直接返 {@code false},
 *       等价于"60s 内重复"。</li>
 *   <li>Redis 前置拦截:{@code SET like:idem:<sid>:<mid> 1 NX EX 60} — 60s 内重复请求 SETNX 失败,直接
 *       返回 {@code false},不再投递 MQ,避免消息堆积。</li>
 *   <li>SETNX 成功 → 投 {@link RabbitMQConfig#QUEUE_LIKE_DB_WRITE} 队列,载荷是 {@link LikeMessage},
 *       由 {@link LikeMessageConsumer} 批量落库。</li>
 *   <li>F-9 业务埋点:首次成功路径上 {@code micrometerConfig.likeCounter("like").increment()},
 *       幂等命中与锁竞争均不计。</li>
 *   <li><b>F-11 W3 feature flag</b>:{@link #like(String, String)} 上 {@code @FeatureFlag("like-cache-bypass")} —
 *       在白名单 ({@code [1, 2, 3]}) 内的 sid 直查 MySQL(走 demo "L1 Redis cache bypass" 路径),
 *       其余 sid 走原有 Redis L1 缓存路径。flag 关闭时直接走原方法(不绕过缓存)。
 *       注意:本方法返回 {@code boolean},Aspect 在 flag 关闭时返 {@link FeatureFlag#defaultOn()} (即
 *       {@code false}) 而非 null,避免 boolean unbox NPE。</li>
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
 *   <li>F-8 锁 key 与 F-5 IDEM key 同后缀 {@code <studentId>:<merchantId>},仅前缀从
 *       {@code like:idem:} 改为 {@code lock:like:} — 保持一一对应,便于调试时按 sid+mid 同时定位
 *       锁状态与幂等状态。</li>
 *   <li>F-9 Counter 必须在锁内 try 块中调用(不是 finally 之前),保证"锁竞争失败 → 返 false → 不计"
 *       的语义;{@code tryLock} 失败路径直接 {@code return false},finally 不执行,Counter 不增。</li>
 *   <li><b>F-11 W3</b>:{@link FeatureFlag} 注解不影响业务方法签名 — 它只是给 AOP 看的元数据,
 *       业务代码完全无感知。{@link #like(String, String)} 的真实方法体是 flag 开启或关闭时的"原方法",
 *       Aspect 在调用前先查 flag;flag 开启才真正调这个方法体。</li>
 * </ul>
 *
 * @author meisijiya
 */
@Service
public class LikeService {

    private static final Logger log = LoggerFactory.getLogger(LikeService.class);

    /** 幂等 key 前缀(完整 key = {@code PREFIX + studentId + ":" + merchantId})。 */
    public static final String IDEM_KEY_PREFIX = "like:idem:";

    /** F-8 分布式锁 key 前缀(完整 key = {@code LOCK_KEY_PREFIX + studentId + ":" + merchantId})。 */
    static final String LOCK_KEY_PREFIX = "lock:like:";

    /** F-8 分布式锁 TTL:5 秒 — Fast path 优化;点赞流程远短于 5s,过期即让其他实例接手。 */
    static final long LOCK_TTL_SEC = 5L;

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
    private final MicrometerConfig micrometerConfig;
    private final RedisLock redisLock;

    public LikeService(StringRedisTemplate redis,
                       RabbitTemplate rabbit,
                       MicrometerConfig micrometerConfig,
                       RedisLock redisLock) {
        this.redis = redis;
        this.rabbit = rabbit;
        this.micrometerConfig = micrometerConfig;
        this.redisLock = redisLock;
    }

    /**
     * 主入口:点赞。60 秒内重复请求返回 {@code false},首次成功返回 {@code true}。
     *
     * <p>前置校验失败抛 {@link ApiException}(由 {@code GlobalExceptionHandler} 转 400);
     * 幂等命中返 {@code false};F-8 锁竞争失败也返 {@code false};真正发消息后返 {@code true}。
     *
     * <p><b>F-11 W3</b>:本方法被 {@link FeatureFlagAspect} 拦截;flag {@code like-cache-bypass} 关闭时
     * Aspect 直接返回 {@link FeatureFlag#defaultOn()} ({@code false}) 不进入本方法体;
     * flag 开启(白名单内 sid)时本方法体被调用 — 这就是 demo "L1 Redis 缓存 bypass,直查 MySQL"
     * 接入点(实际代码体仍是原方法,F-11 W3 阶段仅展示接入骨架,后续 ticket 可在 flag 开启分支里
     * 加 {@code repository.findById(...)} 直查 MySQL 的旁路)。详见
     * {@code docs/observability/demo-scenarios.md § Demo 2}。
     *
     * @param studentId  学生 ID(JWT sub)
     * @param merchantId 被点赞的商户 ID
     * @return {@code true} = 首次成功(已投递 MQ);{@code false} = 60s 内重复点赞 或 F-8 锁竞争失败
     *         或 F-11 flag 关闭被 Aspect 跳过
     * @throws ApiException 400:studentId / merchantId 为空或超长
     */
    @FeatureFlag(value = "like-cache-bypass", defaultOn = false)
    public boolean like(String studentId, String merchantId) {
        validateId("studentId", studentId, 64);
        validateId("merchantId", merchantId, 64);

        String key = IDEM_KEY_PREFIX + studentId + ":" + merchantId;

        // F-8 分布式锁:多实例下严格保证"同一对 (sid, mid) 同一时刻只有一个 JVM 进入点赞路径"。
        // tryLock 是非阻塞;竞争失败等价于"60s 内重复",返 false 不投 MQ,Counter 也不增。
        // (锁内 try 块外提前 return false — Counter 不执行 — 严格"只首次成功打点"。)
        String lockKey = LOCK_KEY_PREFIX + studentId + ":" + merchantId;
        String token = UUID.randomUUID().toString();
        if (!redisLock.tryLock(lockKey, token, LOCK_TTL_SEC)) {
            log.debug("LikeService lock contended, lockKey={} studentId={} merchantId={}",
                    lockKey, studentId, merchantId);
            return false;
        }
        try {
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
            // F-9 W1:业务指标 like_count_total 自增 — 只在首次成功路径打点,幂等命中不计。
            // 这里 Counter 来自 MicrometerConfig.likeCounter():tag endpoint=like。
            // F-8 W2:本调用在锁内 try 块里;tryLock 失败路径已提前 return false,Counter 不会重复 +1。
            micrometerConfig.likeCounter("like").increment();
            return true;
        } finally {
            redisLock.release(lockKey, token);
        }
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