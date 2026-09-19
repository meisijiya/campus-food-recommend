package com.meisijiya.campusfood.module.catalog;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meisijiya.campusfood.module.preheat.RedisShardedWriter;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

/**
 * 商户查询服务(F-4 L1→L2 严格降级)— 暴露给 {@link MerchantController} 的商户读模型。
 *
 * <h2>F-4 阶段降级链路</h2>
 * <pre>
 *   redisTemplate.opsForValue().get("catalog:merchant:&lt;id&gt;")
 *      ├─ 命中(对象) → 解析为 Merchant → 返
 *      ├─ 命中(负缓存) → 返 Optional.empty()
 *      └─ miss → repository.findById(id)
 *           ├─ 命中 → 写 L1 回填 + 返
 *           └─ miss → 写 L1 负缓存 + 返 Optional.empty()
 * </pre>
 *
 * <h2>F-5 升级占位</h2>
 * F-5 升级为 L0 → L1 → L2 严格降级时,在 L1 之前插入 Caffeine per-merchant 几秒 TTL 的 L0;
 * 接口签名不变,Controller 与现有测试不受影响。
 *
 * <p>不带 Lombok,字段全部包私有 + 显式构造器注入;依赖通过 Spring 构造器注入,保证可测。
 *
 * @author meisijiya
 */
@Service
public class MerchantQueryService {

    private static final Logger log = LoggerFactory.getLogger(MerchantQueryService.class);

    private final MerchantRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RedisShardedWriter writer;

    public MerchantQueryService(
            MerchantRepository repository,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            RedisShardedWriter writer) {
        this.repository = repository;
        this.redis = redis;
        this.mapper = mapper;
        this.writer = writer;
    }

    /**
     * 按主键查单商户(F-4 严格 L1→L2 降级,带负缓存)。
     *
     * @param merchantId 商户主键(业务方提供,非自增)
     * @return Optional 包装的商户;空 = 不存在(由 Controller 决定抛 404)
     */
    public Optional<Merchant> findById(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return Optional.empty();
        }

        // 1. L1 查 Redis 单 merchant cache
        String cached = safeRedisGet(RedisShardedWriter.PREFIX_MERCHANT + merchantId);
        if (cached != null) {
            // 负缓存命中
            if (cached.contains("\"__null__\"")) {
                log.debug("MerchantQueryService L1 negative hit, merchantId={}", merchantId);
                return Optional.empty();
            }
            // 正缓存命中
            try {
                Merchant m = mapper.readValue(cached, Merchant.class);
                log.debug("MerchantQueryService L1 hit, merchantId={}", merchantId);
                return Optional.of(m);
            } catch (JsonProcessingException e) {
                log.warn("MerchantQueryService L1 deserialize failed, fallback to L2, merchantId={} err={}",
                        merchantId, e.getMessage());
                // 序列化坏,降级到 L2 不阻塞
            }
        }

        // 2. L2 查 MySQL
        Optional<Merchant> result = repository.findById(merchantId);
        if (result.isPresent()) {
            log.debug("MerchantQueryService L2 hit, merchantId={}", merchantId);
            // 3. 回填 L1
            try {
                writer.writeMerchantCache(merchantId, result.get());
            } catch (RuntimeException e) {
                log.warn("MerchantQueryService L1 backfill failed (non-fatal), merchantId={} err={}",
                        merchantId, e.getMessage());
            }
        } else {
            log.debug("MerchantQueryService L2 miss, merchantId={}", merchantId);
            // 负缓存回填(防穿透)
            try {
                writer.writeMerchantCache(merchantId, null);
            } catch (RuntimeException e) {
                log.warn("MerchantQueryService L1 negative backfill failed (non-fatal), merchantId={} err={}",
                        merchantId, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 按 zoneId 列出商户(F-4 阶段:直接走 L2 MySQL)。
     *
     * <p>F-5 升级为 Redis 区域级 cache key + Caffeine 嵌套缓存。
     *
     * @param zoneId 校区 / 区域 ID
     * @return 该 zone 下商户列表(空集合 = 该 zone 无商户)
     */
    public List<Merchant> findByZoneId(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return List.of();
        }
        List<Merchant> result = repository.findByZoneId(zoneId);
        log.debug("MerchantQueryService L2 zone query, zoneId={}, size={}", zoneId, result.size());
        return result;
    }

    /**
     * 包一层 Redis GET,网络异常时降级到 L2(不抛),避免缓存层抖动拖垮读路径。
     */
    private String safeRedisGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("MerchantQueryService Redis GET failed, fallback to L2, key={} err={}",
                    key, e.getMessage());
            return null;
        }
    }
}