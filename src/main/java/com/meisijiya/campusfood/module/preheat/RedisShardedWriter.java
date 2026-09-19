package com.meisijiya.campusfood.module.preheat;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meisijiya.campusfood.module.preheat.assembler.MerchantCatalog;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;

/**
 * Redis 分片写入器(F-4)— 按 zone 分片把 {@link MerchantCatalog} 写入 Redis,把极热商户
 * Top N 集合写入 {@code catalog:hot:merchants},并写单 merchant L1 cache。
 *
 * <h2>Key 命名(与 spec §4.7 / §4.9 一致)</h2>
 * <ul>
 *   <li>单 zone 分片:{@code catalog:zone:<zoneId>},SETEX 600 秒</li>
 *   <li>单 merchant cache:{@code catalog:merchant:<merchantId>},SETEX 10 分钟
 *       (F-4 acceptance #7 真 L1→L2 降级,F-5 接入 Caffeine L0 后会缩短)</li>
 *   <li>极热商户 SET:{@code catalog:hot:merchants},SET TTL 12h</li>
 * </ul>
 *
 * <h2>序列化</h2>
 * <p>全部走 Jackson {@link ObjectMapper}{@code .writeValueAsString(...)};{@link MerchantCatalog}
 * / {@link Merchant} 是 POJO,直接序列化。空集合也写入(保证 key 存在,TTL 一致)— Redis 缓存
 * 标准做法,避免冷启动时 NPE。
 *
 * <h2>异常处理</h2>
 * <p>{@link JsonProcessingException} 一律包装为 {@link IllegalStateException}
 * ({@code "Redis sharded writer serialize failed"}),不向上抛裸异常;Redis 网络异常由
 * Spring Data Redis 自身处理(运行时连接错误已在上游 {@code application.yml} 配置)。
 *
 * <p>不带 Lombok,字段全部包私有 + 显式构造器注入;依赖通过 Spring 构造器注入,保证可测。
 *
 * @author meisijiya
 */
@Component
public class RedisShardedWriter {

    private static final Logger log = LoggerFactory.getLogger(RedisShardedWriter.class);

    /** 单 zone 分片 key 前缀(完整 key = {@code PREFIX_ZONE + zoneId})。 */
    public static final String PREFIX_ZONE = "catalog:zone:";

    /** 单 merchant cache key 前缀(完整 key = {@code PREFIX_MERCHANT + merchantId})。 */
    public static final String PREFIX_MERCHANT = "catalog:merchant:";

    /** 极热商户 SET 的固定 key(全进程单点,SAdd / SMembers 读)。 */
    public static final String KEY_HOT_MERCHANTS = "catalog:hot:merchants";

    /** 单 zone 分片 TTL:600 秒(spec §4.7)。 */
    public static final Duration ZONE_TTL = Duration.ofSeconds(600);

    /** 单 merchant cache TTL:10 分钟(spec §4.9 + F-5 接入 L0 后会缩短)。 */
    public static final Duration MERCHANT_TTL = Duration.ofMinutes(10);

    /** 极热商户 TTL:12 小时(spec §4.9)。 */
    public static final Duration HOT_TTL = Duration.ofHours(12);

    /**
     * key 后缀安全字符:字母数字 + 下划线 + 连字符 + 点(同 spec §4.7 的 zoneId 风格;
     * 商户 ID 也是同一类业务标识符)。
     */
    static final Pattern KEY_SAFE = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisShardedWriter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /**
     * 写单 zone 分片:{@code catalog:zone:<zoneId> = JSON(catalog), TTL 600s}。
     *
     * @param zoneId  zone 主键(原样拼接到 key 末尾,经 {@link #KEY_SAFE} 校验)
     * @param catalog 待写入的目录(允许为空目录 / null;空目录也写,保证 key 存在)
     * @throws IllegalArgumentException zoneId 为空 / 超长 / 含非法字符
     * @throws IllegalStateException    Jackson 序列化失败时
     */
    public void writeZoneCatalog(String zoneId, MerchantCatalog catalog) {
        validateKeySegment("zoneId", zoneId);
        String key = PREFIX_ZONE + zoneId;
        MerchantCatalog payload = (catalog == null) ? new MerchantCatalog() : catalog;
        String json = serialize(payload);
        redis.opsForValue().set(key, json, ZONE_TTL);
        log.info("RedisShardedWriter wrote zone shard key={} bytes={}", key, json.length());
    }

    /**
     * 写单 merchant L1 cache:{@code catalog:merchant:<merchantId> = JSON(merchant), TTL 10min}。
     *
     * <p>供 {@link com.meisijiya.campusfood.module.catalog.MerchantQueryService} L1 miss → L2 命中后
     * 回填使用,也供凌晨预热批量回填使用。
     *
     * @param merchantId 商户主键(经 {@link #KEY_SAFE} 校验)
     * @param merchant   商户实体(允许为 null — null 也写入,代表"查无此人"的负缓存)
     * @throws IllegalArgumentException merchantId 为空 / 超长 / 含非法字符
     */
    public void writeMerchantCache(String merchantId, Merchant merchant) {
        validateKeySegment("merchantId", merchantId);
        String key = PREFIX_MERCHANT + merchantId;
        String json = serialize(merchant == null ? new NullMerchantMarker() : merchant);
        redis.opsForValue().set(key, json, MERCHANT_TTL);
        log.debug("RedisShardedWriter wrote merchant cache key={} present={}", key, merchant != null);
    }

    /**
     * 写极热商户 SET:{@code catalog:hot:merchants = JSON(Set<String>), TTL 12h}。
     *
     * <p>空集也写入(契约),保证 {@code catalog:hot:merchants} key 始终存在且 TTL 一致;
     * 调用方不需要判空。
     *
     * @param merchantIds 商户 ID 集合(顺序保留 — 使用 {@link LinkedHashSet})
     * @throws IllegalStateException Jackson 序列化失败时
     */
    public void writeHotMerchants(Set<String> merchantIds) {
        Set<String> payload = (merchantIds == null) ? new LinkedHashSet<>() : merchantIds;
        String json = serialize(payload);
        redis.opsForValue().set(KEY_HOT_MERCHANTS, json, HOT_TTL);
        log.info("RedisShardedWriter wrote hot merchants key={} size={}",
                KEY_HOT_MERCHANTS, payload.size());
    }

    /**
     * key 后缀校验:F-4 verifier review finding — zoneId / merchantId 来源当前是 DB schema
     * (VARCHAR(64))约束,但写入路径是公开 API,加防御性校验防止未来拼接用户输入造成
     * Redis key 撑爆 / RESP 帧注入。
     */
    private static void validateKeySegment(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank, got: " + value);
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException(name + " length must be <= 64, got: " + value.length());
        }
        if (!KEY_SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " contains illegal chars: " + value);
        }
    }

    /**
     * 把任意对象序列化为 JSON 字符串,异常统一包装为 {@link IllegalStateException}。
     */
    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Redis sharded writer serialize failed", e);
        }
    }

    /**
     * 负缓存 marker — {@code {"__null__": true}};{@link MerchantQueryService} 读到此值
     * 视为"已确认不存在",避免对同一 merchantId 反复穿透 L1→L2。
     *
     * <p>F-5 verifier finding #3 修复后变成 {@code public}:跨包(
     * {@code module.catalog.MerchantQueryService})用 Jackson 反序列化校验,
     * 不再用脆弱的字符串 contains 匹配。
     */
    public record NullMerchantMarker(boolean __null__) {
        NullMerchantMarker() { this(true); }
    }
}