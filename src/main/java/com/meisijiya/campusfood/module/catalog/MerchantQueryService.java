package com.meisijiya.campusfood.module.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.meisijiya.campusfood.module.featureflag.FeatureFlag;
import com.meisijiya.campusfood.module.preheat.RedisShardedWriter;
import com.meisijiya.campusfood.module.preheat.assembler.Cuisine;
import com.meisijiya.campusfood.module.preheat.assembler.MerchantCatalog;
import com.meisijiya.campusfood.module.preheat.assembler.Zone;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 商户查询服务(F-5 L0 → L1 → L2 严格降级)— 暴露给 {@link MerchantController} 的商户读模型。
 *
 * <h2>F-5 三级降级链路</h2>
 * <pre>
 *   1. L0 Caffeine getIfPresent(merchantHotCache, id)
 *      ├─ 命中 Optional.of(m) → 立即 return m(L0 正缓存 hit,1 ms 级)
 *      ├─ 命中 Optional.empty → 立即 return empty(L0 负缓存 hit,防穿透)
 *      └─ null(真 miss)
 *          2. L1 Redis GET(catalog:merchant:&lt;id&gt;)
 *             ├─ 命中正缓存 JSON → 反序列化为 Merchant → 写回 L0 → return m
 *             ├─ 命中负缓存 {"__null__":true} → 写回 L0 负缓存 → return empty
 *             └─ miss / 读失败
 *                 3. L2 MySQL repository.findById(id)
 *                    ├─ 命中 → writer.writeMerchantCache(id, m) 写 L1 + 写 L0 → return m
 *                    └─ miss → writer.writeMerchantCache(id, null) 写 L1 负 + 写 L0 负 → return empty
 * </pre>
 *
 * <h2>findByZoneId 同步升级</h2>
 * <p>F-5 acceptance #6 同时要求 {@code zoneCatalogCache} bean 存在并被消费。
 * 本方法做 L0 → L1 → L2 完整降级:L0 命中即返回,避免 Redis 往返;L1 反序列化
 * {@link MerchantCatalog} 后扁平化为 {@code List<Merchant>} 再回填 L0;
 * L2 命中后调用 {@link RedisShardedWriter#writeZoneCatalog} 写 L1。
 *
 * <h2>序列化边界</h2>
 * <p><strong>L0 = JVM-local POJO 引用</strong>(不序列化,直持 {@link Optional}{@code <Merchant>}
 * / {@code List<Merchant>} 引用),<strong>L1 = Jackson JSON</strong>(跨进程一致性)。
 * Caffeine 持 POJO 引用 → 节省 JSON CPU 开销,且拿到的是强类型对象;Redis JSON →
 * 跨节点共享 + 进程重启不丢。两者通过 service 的"backfill"逻辑联动。
 *
 * <h2>异常降级</h2>
 * <p>Redis 读 / 写异常一律降级到下一层,不抛 — 与 F-4 既有 {@code safeRedisGet} 模式一致;
 * Caffeine 操作(JVM 内存)不抛运行时异常,无需额外保护。
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
    /** L0 Caffeine 单商户 cache;value 用 Optional 表达正/负缓存。 */
    private final Cache<String, Optional<Merchant>> merchantHotCache;
    /** L0 Caffeine 校区目录 cache;value 是扁平化后的 List<Merchant>。 */
    private final Cache<String, List<Merchant>> zoneCatalogCache;
    /** F-9 W2:Micrometer 指标 registry — 用于打 cache_hit_ratio 计数器。 */
    private final MeterRegistry meterRegistry;

    public MerchantQueryService(
            MerchantRepository repository,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            RedisShardedWriter writer,
            Cache<String, Optional<Merchant>> merchantHotCache,
            Cache<String, List<Merchant>> zoneCatalogCache,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.redis = redis;
        this.mapper = mapper;
        this.writer = writer;
        this.merchantHotCache = merchantHotCache;
        this.zoneCatalogCache = zoneCatalogCache;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 按主键查单商户(F-5 严格 L0 → L1 → L2 降级,带负缓存)。
     *
     * <p>L0 命中即 return(miss 不再下探);L1 命中要写回 L0(backfill,防下次同 merchantId
     * 再走 Redis);L2 命中既写 L1 也写 L0。任一层异常均降级到下一层,不抛。
     *
     * @param merchantId 商户主键(业务方提供,非自增)
     * @return Optional 包装的商户;空 = 不存在(由 Controller 决定抛 404)
     */
    public Optional<Merchant> findById(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return Optional.empty();
        }

        // 1. L0 Caffeine — JVM-local,1 ms 级
        Optional<Merchant> l0 = merchantHotCache.getIfPresent(merchantId);
        if (l0 != null) {
            if (l0.isPresent()) {
                log.debug("MerchantQueryService L0 hit, merchantId={}", merchantId);
                // F-9 W2:cache_hit_ratio{merchantHotCache, L0} +1
                bumpHitCounter("merchantHotCache", "L0");
                return l0;
            }
            log.debug("MerchantQueryService L0 negative hit, merchantId={}", merchantId);
            // 负缓存也算 hit(NOT_FOUND 也算避免了下游穿透,等价 hit)
            bumpHitCounter("merchantHotCache", "L0");
            return Optional.empty();
        }

        // 2. L1 Redis — 跨进程共享的 JSON cache
        String cached = safeRedisGet(RedisShardedWriter.PREFIX_MERCHANT + merchantId);
        if (cached != null) {
            // Standards verifier finding #3 修复(第二轮):用 Jackson parse NullMerchantMarker 判负,
            // 替代原 contains("\"__null__\"") 字符串匹配。Spring Boot 默认 Jackson lenient 模式
            // 下,商户 JSON(无 __null__ 字段)能被解析成 NullMerchantMarker(__null__=false),
            // 所以必须**同时**检查 marker 非空 + marker.__null__() 为真 — 单看 marker != null 会
            // 把所有商户判成负缓存(返 404)。第一轮 fix 漏了 __null__ 字段检查导致 L0 全负 hit,
            // 已通过 smoke test 立即发现 + 回退修正。
            try {
                RedisShardedWriter.NullMerchantMarker marker =
                        mapper.readValue(cached, RedisShardedWriter.NullMerchantMarker.class);
                if (marker != null && marker.__null__()) {
                    log.debug("MerchantQueryService L1 negative hit, merchantId={}", merchantId);
                    merchantHotCache.put(merchantId, Optional.empty()); // backfill L0 negative
                    bumpHitCounter("merchantHotCache", "L1");
                    return Optional.empty();
                }
                // marker 解析成功但 __null__=false → 这其实是商户 JSON,落到下方 Merchant 反序列化
            } catch (JsonProcessingException ignored) {
                // 非负缓存 marker 形状,继续尝试正缓存反序列化
            }
            // 正缓存命中
            try {
                Merchant m = mapper.readValue(cached, Merchant.class);
                log.debug("MerchantQueryService L1 hit, merchantId={}", merchantId);
                merchantHotCache.put(merchantId, Optional.of(m)); // backfill L0
                bumpHitCounter("merchantHotCache", "L1");
                return Optional.of(m);
            } catch (JsonProcessingException e) {
                log.warn("MerchantQueryService L1 deserialize failed, fallback to L2, merchantId={} err={}",
                        merchantId, e.getMessage());
                // 序列化坏,降级到 L2 不阻塞
            }
        }

        // 3. L2 MySQL — 真值源
        Optional<Merchant> result = repository.findById(merchantId);
        if (result.isPresent()) {
            log.debug("MerchantQueryService L2 hit, merchantId={}", merchantId);
            // F-9 W2:cache_hit_ratio{merchantHotCache, L2} +1
            bumpHitCounter("merchantHotCache", "L2");
            // backfill L1 (writer 内部会捕获序列化异常包装 IllegalStateException,这里再兜一层)
            try {
                writer.writeMerchantCache(merchantId, result.get());
            } catch (RuntimeException e) {
                log.warn("MerchantQueryService L1 backfill failed (non-fatal), merchantId={} err={}",
                        merchantId, e.getMessage());
            }
            // backfill L0(Caffeine 直写,JVM-local,失败不影响主流程)
            merchantHotCache.put(merchantId, result);
        } else {
            log.debug("MerchantQueryService L2 miss, merchantId={}", merchantId);
            // F-9 W2:cache_hit_ratio{merchantHotCache, miss} +1(全空,全 miss)
            bumpHitCounter("merchantHotCache", "miss");
            // L1 负缓存回填(防穿透)
            try {
                writer.writeMerchantCache(merchantId, null);
            } catch (RuntimeException e) {
                log.warn("MerchantQueryService L1 negative backfill failed (non-fatal), merchantId={} err={}",
                        merchantId, e.getMessage());
            }
            // L0 负缓存回填
            merchantHotCache.put(merchantId, Optional.empty());
        }
        return result;
    }

    /**
     * F-9 W2:cache_hit_ratio 计数器 helper — 调用一次即在 {@link MeterRegistry}
     * 注册名为 {@code cache_hit_ratio},tags {@code cache_name=<cacheName>, hit_tier=<hitTier>}
     * 的 {@link Counter} 自增一次。Micrometer 内部对相同 name + tags 复用同一 Counter 实例,无副作用。
     */
    private void bumpHitCounter(String cacheName, String hitTier) {
        if (meterRegistry == null) {
            // 防御性兜底:理论上 Spring 注入 MeterRegistry 不会为 null,但保留 fallback 防止 null-pointer 测试崩溃
            return;
        }
        Counter.builder("cache_hit_ratio")
                .description("F-9 W2:MerchantQueryService 三层命中路径累计")
                .tag("cache_name", cacheName)
                .tag("hit_tier", hitTier)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 按 zoneId 列出商户(F-5 严格 L0 → L1 → L2 降级)。
     *
     * <p>L1 反序列化为 {@link MerchantCatalog}(三层树)后扁平化为 {@code List<Merchant>}
     * 再回填 L0;L2 命中后调用 {@link RedisShardedWriter#writeZoneCatalog} 写 L1
     * (传入扁平化后的列表组装成单 zone 单 cuisine 结构的最小目录)。
     *
     * @param zoneId 校区 / 区域 ID
     * @return 该 zone 下商户列表(空集合 = 该 zone 无商户)
     */
    public List<Merchant> findByZoneId(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return List.of();
        }

        // 1. L0 Caffeine — JVM-local 扁平 List
        List<Merchant> l0 = zoneCatalogCache.getIfPresent(zoneId);
        if (l0 != null) {
            log.debug("MerchantQueryService L0 zone hit, zoneId={}, size={}", zoneId, l0.size());
            return l0;
        }

        // 2. L1 Redis — MerchantCatalog 三层树 JSON
        String cached = safeRedisGet(RedisShardedWriter.PREFIX_ZONE + zoneId);
        if (cached != null) {
            try {
                MerchantCatalog catalog = mapper.readValue(cached, MerchantCatalog.class);
                List<Merchant> flat = flattenCatalog(catalog);
                zoneCatalogCache.put(zoneId, flat); // backfill L0
                log.debug("MerchantQueryService L1 zone hit, zoneId={}, size={}", zoneId, flat.size());
                return flat;
            } catch (Exception e) {
                log.warn("MerchantQueryService L1 zone deserialize failed, fallback to L2, zoneId={} err={}",
                        zoneId, e.getMessage());
                // 序列化坏 / 格式异常,降级到 L2
            }
        }

        // 3. L2 MySQL — 真值源
        List<Merchant> result = repository.findByZoneId(zoneId);
        log.debug("MerchantQueryService L2 zone query, zoneId={}, size={}", zoneId, result.size());

        // backfill L0(直接持 POJO 引用)
        zoneCatalogCache.put(zoneId, result);

        // backfill L1(组装成 MerchantCatalog 再写 Redis)
        try {
            writer.writeZoneCatalog(zoneId, toMinimalCatalog(zoneId, result));
        } catch (RuntimeException e) {
            log.warn("MerchantQueryService L1 zone backfill failed (non-fatal), zoneId={} err={}",
                    zoneId, e.getMessage());
        }

        return result;
    }

    /**
     * 把 {@link MerchantCatalog} 三层树(zones → cuisines → merchants)扁平化为单层
     * {@code List<Merchant>},作为 L0 zone cache 的 value。
     */
    private static List<Merchant> flattenCatalog(MerchantCatalog catalog) {
        if (catalog == null || catalog.getZones() == null || catalog.getZones().isEmpty()) {
            return List.of();
        }
        List<Merchant> all = new ArrayList<>();
        for (Zone zone : catalog.getZones()) {
            if (zone == null || zone.getCuisines() == null) {
                continue;
            }
            for (Cuisine cuisine : zone.getCuisines()) {
                if (cuisine == null || cuisine.getMerchants() == null) {
                    continue;
                }
                all.addAll(cuisine.getMerchants());
            }
        }
        return all;
    }

    /**
     * 把 {@code List<Merchant>} 组装成最小化的 {@link MerchantCatalog}(单 zone + 按 cuisineId 分组的多个 cuisines),
     * 供 L1 回填写入 Redis。保留 cuisine 维度与原 {@link com.meisijiya.campusfood.module.preheat.HeatJobPreheater}
     * 写出的目录结构一致 — F-5+ 任何按 cuisine 分组的渲染都能从 L1 hit 反推,不丢维度信息。
     *
     * <p>Standards verifier finding #2:原实现把第一条商户的 cuisineId 当整个 zone 的 cuisine,
     * N 个 cuisine 坍缩为 1 个,违反"Redis 是事实来源"。修复:按 cuisineId 分组,每个 cuisine 一个 {@link Cuisine},
     * 再塞进 {@link Zone};空 zone 走单 zone + 空 cuisines 分支。
     */
    private static MerchantCatalog toMinimalCatalog(String zoneId, List<Merchant> merchants) {
        MerchantCatalog catalog = new MerchantCatalog();
        if (merchants == null || merchants.isEmpty()) {
            Zone zone = new Zone(zoneId, List.of());
            catalog.setZones(new ArrayList<>(List.of(zone)));
            return catalog;
        }
        // 按 cuisineId 分组(用 LinkedHashMap 保留首次出现顺序,稳定渲染)
        java.util.Map<String, List<Merchant>> byCuisine = new java.util.LinkedHashMap<>();
        for (Merchant m : merchants) {
            String cid = m.getCuisineId() != null ? m.getCuisineId() : "UNKNOWN";
            byCuisine.computeIfAbsent(cid, k -> new ArrayList<>()).add(m);
        }
        List<Cuisine> cuisines = new ArrayList<>(byCuisine.size());
        for (java.util.Map.Entry<String, List<Merchant>> e : byCuisine.entrySet()) {
            cuisines.add(new Cuisine(e.getKey(), e.getValue()));
        }
        Zone zone = new Zone(zoneId, cuisines);
        catalog.setZones(new ArrayList<>(List.of(zone)));
        return catalog;
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

    /**
     * 详情 DTO(功能开关版,F-11 W3 demo)— flag {@code merchant-detail-new} 开启时,在基本商户字段上
     * 追加 {@code openHours} 等增量字段。flag 关闭时只返基本字段。
     *
     * <p>返回 {@link Map}{@code <String, Object>} 而非改 {@link Merchant} 实体:
     * <ul>
     *   <li>保留原 {@link Merchant} 实体 schema(只读 + 不可改 — W3 plan §18.4);</li>
     *   <li>不强制 controller / DTO 改造 — Map 在 Jackson 序列化为 JSON 时与 {@code Merchant} 同名键对齐;</li>
     *   <li>增量字段(如 {@code openHours})按需 put,后续 ticket 可继续扩展。</li>
     * </ul>
     *
     * <h2>flag 行为</h2>
     * <ul>
     *   <li>flag 关闭:返 {@code {id, zoneId, cuisineId, name, tags, heatScore, found}}</li>
     *   <li>flag 开启:返基本字段 + {@code openHours="09:00-22:00"} + {@code featureFlag="merchant-detail-new:ON"}</li>
     * </ul>
     *
     * <p><b>F-11 W3</b>:本方法被 {@link FeatureFlagAspect} 拦截;flag 开启命中分支时,Aspect
     * 在 jp.proceed() 返 Map 后追加 {@code openHours} + {@code featureFlag} 字段。
     * flag 关闭分支返 Map(无新增字段),与原 {@code Merchant} 字段集一致。
     *
     * @param merchantId 商户主键
     * @return Map 形式商户详情;flag 关闭时 key 集 = Merchant 字段 + {@code found};flag 开启时 + {@code openHours}
     */
    @FeatureFlag(value = "merchant-detail-new", defaultOn = false)
    public Map<String, Object> findDetailById(String merchantId) {
        // Aspect 拦截前 studentId 解析一定返 null(此方法无 sid 参数)— flag 仅依赖 merchantId 本身 +
        // 全局 ALL_ON / ALL_OFF 决策。这与 yml default-flags 配置(merchant-detail-new: ALL_OFF)一致。
        Map<String, Object> result = new LinkedHashMap<>();
        if (merchantId == null || merchantId.isBlank()) {
            result.put("found", false);
            result.put("id", merchantId);
            return result;
        }
        Optional<Merchant> opt = findById(merchantId);
        if (opt.isEmpty()) {
            result.put("found", false);
            result.put("id", merchantId);
            return result;
        }
        Merchant m = opt.get();
        // 基本字段(对齐 Merchant 实体)
        result.put("id", m.getId());
        result.put("zoneId", m.getZoneId());
        result.put("cuisineId", m.getCuisineId());
        result.put("name", m.getName());
        result.put("tags", m.getTags());
        result.put("heatScore", m.getHeatScore());
        result.put("found", true);
        // Aspect 在 flag 开启时会在此 Map 上再追加 openHours + featureFlag 两个 key
        return result;
    }
}