package com.meisijiya.campusfood.module.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.meisijiya.campusfood.module.preheat.RedisShardedWriter;
import com.meisijiya.campusfood.module.preheat.assembler.MerchantCatalog;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link MerchantQueryService} 单元测试(F-5 L0 → L1 → L2 严格降级)— 用真实 Caffeine 实例
 * 验证 3 级缓存命中路径 + backfill + 负缓存语义。
 *
 * <h2>F-5 acceptance 关联</h2>
 * <ul>
 *   <li>L0 hit → 直接返,不调 Redis / MySQL</li>
 *   <li>L0 miss + L1 hit → 返,Redis 已命中不调 MySQL,L0 被回填</li>
 *   <li>L0 miss + L1 miss + L2 hit → 返,MySQL 命中,writer 写 L1 + Caffeine 写 L0</li>
 *   <li>L0 miss + L1 miss + L2 miss → 返空,writer 写 L1 负缓存 + Caffeine 写 L0 负缓存</li>
 *   <li>L1 正缓存命中 → backfill L0 后下次走 L0</li>
 *   <li>findByZoneId 三级降级同步覆盖</li>
 * </ul>
 *
 * <p>Caffeine 用 {@link Caffeine#newBuilder()}{@code .build()} 真实构造,不做 mock
 * — Caffeine 太简单,真实构造更可信,无需 {@code Mockito} 的额外桩逻辑。
 *
 * @author meisijiya
 */
class MerchantQueryServiceTest {

    private MerchantRepository repo;
    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> ops;
    private ObjectMapper mapper;
    private RedisShardedWriter writer;
    private Cache<String, Optional<Merchant>> merchantHotCache;
    private Cache<String, List<Merchant>> zoneCatalogCache;
    private SimpleMeterRegistry meterRegistry;
    private MerchantQueryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(MerchantRepository.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        mapper = new ObjectMapper();
        writer = mock(RedisShardedWriter.class);
        when(redis.opsForValue()).thenReturn(ops);

        // 真实 Caffeine 实例,不做 mock
        merchantHotCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(java.time.Duration.ofMinutes(5))
                .build();
        zoneCatalogCache = Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(java.time.Duration.ofMinutes(10))
                .build();

        // F-9 W2:SimpleMeterRegistry 作为测试替身,验证 findById 在三层命中路径都会打 Counter
        meterRegistry = new SimpleMeterRegistry();

        service = new MerchantQueryService(repo, redis, mapper, writer,
                merchantHotCache, zoneCatalogCache, meterRegistry);
    }

    private static Merchant newMerchant(String id) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setZoneId("Z-1");
        m.setCuisineId("C-1");
        m.setName("shop-" + id);
        m.setTags("");
        m.setHeatScore(0.0);
        return m;
    }

    // ---------- F-5 acceptance: L0 → L1 → L2 严格降级(findById) ----------

    @Test
    @DisplayName("findById_L0命中_直接返商户_不调Redis也不调MySQL")
    void findById_l0Hit_returnsMerchant_directly() throws Exception {
        // L0 预填(模拟 Caffeine 已缓存)
        Merchant cached = newMerchant("M-1");
        merchantHotCache.put("M-1", Optional.of(cached));

        Optional<Merchant> result = service.findById("M-1");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("M-1");
        assertThat(result.get().getName()).isEqualTo("shop-M-1");
        // L0 命中不能触达下层
        verify(redis, never()).opsForValue();
        verify(repo, never()).findById(anyString());
        // L0 命中不能触发回填
        verify(writer, never()).writeMerchantCache(anyString(), any());
    }

    @Test
    @DisplayName("findById_L0负缓存命中_直接返空_不调Redis也不调MySQL")
    void findById_l0NegativeHit_returnsEmpty_directly() {
        // L0 预填负缓存
        merchantHotCache.put("M-NONE", Optional.empty());

        Optional<Merchant> result = service.findById("M-NONE");

        assertThat(result).isEmpty();
        verify(redis, never()).opsForValue();
        verify(repo, never()).findById(anyString());
        verify(writer, never()).writeMerchantCache(anyString(), any());
    }

    @Test
    @DisplayName("findById_L0miss加L1命中_返商户_不调MySQL且回填L0")
    void findById_l0Miss_l1Hit_returnsMerchant_andBackfillsL0() throws Exception {
        Merchant m = newMerchant("M-1");
        String cached = mapper.writeValueAsString(m);
        when(ops.get("catalog:merchant:M-1")).thenReturn(cached);

        // 调用前 L0 为空
        assertThat(merchantHotCache.getIfPresent("M-1")).isNull();

        Optional<Merchant> result = service.findById("M-1");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("M-1");
        // L1 命中不能触达 MySQL
        verify(repo, never()).findById(anyString());
        // L1 命中不能写 L1(已存在,无需再写)
        verify(writer, never()).writeMerchantCache(anyString(), any());
        // L0 被 backfill
        assertThat(merchantHotCache.getIfPresent("M-1")).isPresent();
        assertThat(merchantHotCache.getIfPresent("M-1").get().getId()).isEqualTo("M-1");
    }

    @Test
    @DisplayName("findById_L0miss加L1负缓存命中_返空_不调MySQL且回填L0负缓存")
    void findById_l0Miss_l1NegativeHit_returnsEmpty_andBackfillsL0Negative() {
        when(ops.get("catalog:merchant:M-NONE")).thenReturn("{\"__null__\":true}");

        // 调用前 L0 为空
        assertThat(merchantHotCache.getIfPresent("M-NONE")).isNull();

        Optional<Merchant> result = service.findById("M-NONE");

        assertThat(result).isEmpty();
        verify(repo, never()).findById(anyString());
        verify(writer, never()).writeMerchantCache(anyString(), any());
        // L0 被 backfill 负缓存
        Optional<Merchant> l0Backfill = merchantHotCache.getIfPresent("M-NONE");
        assertThat(l0Backfill).isNotNull();
        assertThat(l0Backfill).isEmpty();
    }

    @Test
    @DisplayName("findById_L0miss加L1miss加L2命中_返商户_回填L1和L0")
    void findById_l0Miss_l1Miss_l2Hit_returnsMerchant_andBackfillsL0AndL1() {
        Merchant m = newMerchant("M-1");
        when(ops.get("catalog:merchant:M-1")).thenReturn(null);
        when(repo.findById("M-1")).thenReturn(Optional.of(m));

        Optional<Merchant> result = service.findById("M-1");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("M-1");
        // MySQL 命中
        verify(repo, times(1)).findById("M-1");
        // L1 backfill(正缓存,非 null)
        verify(writer, times(1)).writeMerchantCache(eq("M-1"), eq(m));
        // L0 backfill
        Optional<Merchant> l0Backfill = merchantHotCache.getIfPresent("M-1");
        assertThat(l0Backfill).isPresent();
        assertThat(l0Backfill.get().getId()).isEqualTo("M-1");
    }

    @Test
    @DisplayName("findById_L0miss加L1miss加L2miss_返空_写L1负缓存和L0负缓存")
    void findById_l0_l1_l2_AllMiss_returnsEmpty_andNegativeCacheWritten() {
        when(ops.get("catalog:merchant:M-X")).thenReturn(null);
        when(repo.findById("M-X")).thenReturn(Optional.empty());

        Optional<Merchant> result = service.findById("M-X");

        assertThat(result).isEmpty();
        verify(repo, times(1)).findById("M-X");
        // L1 负缓存回填(null marker)
        verify(writer, times(1)).writeMerchantCache(eq("M-X"), eq(null));
        // L0 负缓存回填
        Optional<Merchant> l0Backfill = merchantHotCache.getIfPresent("M-X");
        assertThat(l0Backfill).isNotNull();
        assertThat(l0Backfill).isEmpty();
    }

    @Test
    @DisplayName("findById_空或null_id_返空且不触达任何层")
    void findById_blankId_returnsEmpty_andDoesNotTouchAnyLayer() {
        assertThat(service.findById(null)).isEmpty();
        assertThat(service.findById("")).isEmpty();
        assertThat(service.findById("   ")).isEmpty();
        verify(redis, never()).opsForValue();
        verify(repo, never()).findById(anyString());
        verify(writer, never()).writeMerchantCache(anyString(), any());
    }

    @Test
    @DisplayName("findById_L1读失败_降级到L2且不抛_L0被正确回填")
    void findById_l1ReadFails_fallbackToL2() {
        Merchant m = newMerchant("M-1");
        when(ops.get(anyString())).thenThrow(new RuntimeException("Redis down"));
        when(repo.findById("M-1")).thenReturn(Optional.of(m));

        Optional<Merchant> result = service.findById("M-1");

        assertThat(result).isPresent();
        verify(repo, times(1)).findById("M-1");
        // L2 命中后写 L1 回填
        verify(writer, times(1)).writeMerchantCache(eq("M-1"), eq(m));
        // L0 backfill
        assertThat(merchantHotCache.getIfPresent("M-1")).isPresent();
    }

    // ---------- F-5 acceptance: findByZoneId L0 → L1 → L2 ----------

    @Test
    @DisplayName("findByZoneId_L0命中_直接返列表_不调Redis也不调MySQL")
    void findByZoneId_l0Hit_returnsList_directly() {
        // L0 预填
        List<Merchant> cached = List.of(newMerchant("M-1"), newMerchant("M-2"));
        zoneCatalogCache.put("Z-1", cached);

        List<Merchant> result = service.findByZoneId("Z-1");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Merchant::getId).containsExactly("M-1", "M-2");
        verify(redis, never()).opsForValue();
        verify(repo, never()).findByZoneId(anyString());
        verify(writer, never()).writeZoneCatalog(anyString(), any());
    }

    @Test
    @DisplayName("findByZoneId_L0miss加L2命中_返列表_回填L0和L1")
    void findByZoneId_l0Miss_l2Hit_returnsList_andBackfillsL0AndL1() throws Exception {
        Merchant m1 = newMerchant("M-1");
        Merchant m2 = newMerchant("M-2");
        m2.setZoneId("Z-1");
        when(ops.get("catalog:zone:Z-1")).thenReturn(null);
        when(repo.findByZoneId("Z-1")).thenReturn(List.of(m1, m2));

        List<Merchant> result = service.findByZoneId("Z-1");

        assertThat(result).hasSize(2);
        verify(repo, times(1)).findByZoneId("Z-1");
        // L1 backfill(传 MerchantCatalog)
        verify(writer, times(1)).writeZoneCatalog(eq("Z-1"), any(MerchantCatalog.class));
        // L0 backfill
        List<Merchant> l0Backfill = zoneCatalogCache.getIfPresent("Z-1");
        assertThat(l0Backfill).isNotNull();
        assertThat(l0Backfill).hasSize(2);
    }

    @Test
    @DisplayName("findByZoneId_L0miss加L1miss加L2miss_返空_回填空列表")
    void findByZoneId_allMiss_returnsEmpty_andCachesEmptyList() throws Exception {
        when(ops.get("catalog:zone:Z-X")).thenReturn(null);
        when(repo.findByZoneId("Z-X")).thenReturn(List.of());

        List<Merchant> result = service.findByZoneId("Z-X");

        assertThat(result).isEmpty();
        verify(repo, times(1)).findByZoneId("Z-X");
        // L1 backfill(空 zone 也写,保证 key 存在)
        verify(writer, times(1)).writeZoneCatalog(eq("Z-X"), any(MerchantCatalog.class));
        // L0 backfill(空集合)
        List<Merchant> l0Backfill = zoneCatalogCache.getIfPresent("Z-X");
        assertThat(l0Backfill).isNotNull();
        assertThat(l0Backfill).isEmpty();
    }

    @Test
    @DisplayName("findByZoneId_空zoneId_返空列表_不触达任何层")
    void findByZoneId_blankZoneId_returnsEmpty() {
        List<Merchant> result = service.findByZoneId("");
        assertThat(result).isEmpty();
        verify(redis, never()).opsForValue();
        verify(repo, never()).findByZoneId(anyString());
    }

    // ---------- F-5 acceptance: 端到端 L1 → L0 backfill 闭环 ----------

    @Test
    @DisplayName("findById_第一次L1miss_L2命中_第二次直接走L0(backfill闭环)")
    void findById_endToEnd_l2BackfillsBothLayers_thenL0Hit() {
        Merchant m = newMerchant("M-1");
        when(ops.get("catalog:merchant:M-1")).thenReturn(null);
        when(repo.findById("M-1")).thenReturn(Optional.of(m));

        // 第一次:L0 miss, L1 miss, L2 hit
        Optional<Merchant> first = service.findById("M-1");
        assertThat(first).isPresent();
        verify(repo, times(1)).findById("M-1");
        verify(writer, times(1)).writeMerchantCache(eq("M-1"), eq(m));

        // 第二次:验证 L0 已 backfill,不走 MySQL
        Optional<Merchant> second = service.findById("M-1");
        assertThat(second).isPresent();
        assertThat(second.get().getId()).isEqualTo("M-1");
        // MySQL 仍然只调一次(第二次走 L0)
        verify(repo, times(1)).findById("M-1");
        // Writer 仍然只调一次(第二次走 L0)
        verify(writer, times(1)).writeMerchantCache(eq("M-1"), eq(m));
    }
}