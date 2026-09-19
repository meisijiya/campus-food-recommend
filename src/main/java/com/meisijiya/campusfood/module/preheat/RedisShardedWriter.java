package com.meisijiya.campusfood.module.preheat;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meisijiya.campusfood.module.preheat.assembler.MerchantCatalog;

/**
 * Redis 分片写入器(F-4)— 按 zone 分片把 {@link MerchantCatalog} 写入 Redis,并把极热商户
 * Top N 集合写入 {@code catalog:hot:merchants},TTL 12h。
 *
 * <h2>Key 命名(与 spec §4.7 / §4.9 一致)</h2>
 * <ul>
 *   <li>单 zone 分片:{@code catalog:zone:<zoneId>},SETEX 600 秒({@link Duration#ofSeconds(long)})</li>
 *   <li>极热商户 SET:{@code catalog:hot:merchants},SET TTL 12h({@link Duration#ofHours(long)})</li>
 * </ul>
 *
 * <h2>序列化</h2>
 * <p>全部走 Jackson {@link ObjectMapper}{@code .writeValueAsString(...)};{@link MerchantCatalog}
 * 是 POJO,直接序列化。空 {@link MerchantCatalog} 也写入(保证 key 存在,TTL 一致)— 这是
 * Redis 缓存标准做法,避免冷启动时 NPE。
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
class RedisShardedWriter {

    private static final Logger log = LoggerFactory.getLogger(RedisShardedWriter.class);

    /** 单 zone 分片 key 前缀(完整 key = {@code PREFIX_ZONE + zoneId})。 */
    static final String PREFIX_ZONE = "catalog:zone:";

    /** 极热商户 SET 的固定 key(全进程单点,SAdd / SMembers 读)。 */
    static final String KEY_HOT_MERCHANTS = "catalog:hot:merchants";

    /** 单 zone 分片 TTL:600 秒(spec §4.7)。 */
    static final Duration ZONE_TTL = Duration.ofSeconds(600);

    /** 极热商户 TTL:12 小时(spec §4.9)。 */
    static final Duration HOT_TTL = Duration.ofHours(12);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    RedisShardedWriter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /**
     * 写单 zone 分片:{@code catalog:zone:<zoneId> = JSON(catalog), TTL 600s}。
     *
     * @param zoneId  zone 主键(原样拼接到 key 末尾)
     * @param catalog 待写入的目录(允许为空目录 / null;空目录也写,保证 key 存在)
     * @throws IllegalStateException 当 Jackson 序列化失败时
     */
    void writeZoneCatalog(String zoneId, MerchantCatalog catalog) {
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("zoneId must be non-blank, got: " + zoneId);
        }
        String key = PREFIX_ZONE + zoneId;
        MerchantCatalog payload = (catalog == null) ? new MerchantCatalog() : catalog;
        String json = serialize(payload);
        redis.opsForValue().set(key, json, ZONE_TTL);
        log.info("RedisShardedWriter wrote zone shard key={} bytes={}", key, json.length());
    }

    /**
     * 写极热商户 SET:{@code catalog:hot:merchants = JSON(Set<String>), TTL 12h}。
     *
     * <p>空集也写入(契约),保证 {@code catalog:hot:merchants} key 始终存在且 TTL 一致;
     * 调用方不需要判空。
     *
     * @param merchantIds 商户 ID 集合(顺序保留 — 使用 {@link LinkedHashSet})
     * @throws IllegalStateException 当 Jackson 序列化失败时
     */
    void writeHotMerchants(Set<String> merchantIds) {
        Set<String> payload = (merchantIds == null) ? new LinkedHashSet<>() : merchantIds;
        String json = serialize(payload);
        redis.opsForValue().set(KEY_HOT_MERCHANTS, json, HOT_TTL);
        log.info("RedisShardedWriter wrote hot merchants key={} size={}",
                KEY_HOT_MERCHANTS, payload.size());
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
}