package com.meisijiya.campusfood.module.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link MerchantQueryService} 的指标埋点单元测试(F-9 W2)— 验证 {@code cache_hit_ratio} 计数器在
 * L0 / L1 / L2 / miss 四种命中路径上分别带正确 tag 自增。
 *
 * <h2>覆盖路径</h2>
 * <ul>
 *   <li>L0 正命中 → hit_tier=L0</li>
 *   <li>L0 负命中 → hit_tier=L0</li>
 *   <li>L0 miss + L1 正命中 → hit_tier=L1</li>
 *   <li>L0 miss + L1 负命中 → hit_tier=L1</li>
 *   <li>L0 miss + L1 miss + L2 命中 → hit_tier=L2</li>
 *   <li>L0 miss + L1 miss + L2 miss → hit_tier=miss</li>
 * </ul>
 *
 * <p>{@link MeterRegistry} 用 {@link SimpleMeterRegistry} 真实实例而不是 mock —
 * 走真实 registry 验证 {@link Counter} 注册 + tag 维度正确性;行为契约比 mock 更可靠。
 *
 * @author meisijiya
 */
class MerchantQueryServiceMetricsTest {

    private MerchantRepository repo;
    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> ops;
    private ObjectMapper mapper;
    private RedisShardedWriter writer;
    private Cache<String, Optional<Merchant>> merchantHotCache;
    private Cache<String, List<Merchant>> zoneCatalogCache;
    private MeterRegistry meterRegistry;
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

        merchantHotCache = Caffeine.newBuilder().maximumSize(100).build();
        zoneCatalogCache = Caffeine.newBuilder().maximumSize(20).build();

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

    private double hitCount(String hitTier) {
        Counter c = meterRegistry.find("cache_hit_ratio")
                .tag("cache_name", "merchantHotCache")
                .tag("hit_tier", hitTier)
                .counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("findById_L0正命中_counter_L0_自增")
    void l0PositiveHit_incrementsL0Counter() {
        Merchant cached = newMerchant("M-1");
        merchantHotCache.put("M-1", Optional.of(cached));

        service.findById("M-1");

        assertThat(hitCount("L0")).isEqualTo(1.0);
        assertThat(hitCount("L1")).isEqualTo(0.0);
        assertThat(hitCount("L2")).isEqualTo(0.0);
        assertThat(hitCount("miss")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("findById_L0负命中_counter_L0_自增_NOT_FOUND避免穿透也计入 hit")
    void l0NegativeHit_incrementsL0Counter() {
        merchantHotCache.put("M-X", Optional.empty());

        service.findById("M-X");

        assertThat(hitCount("L0")).isEqualTo(1.0);
        assertThat(hitCount("L1")).isEqualTo(0.0);
        assertThat(hitCount("L2")).isEqualTo(0.0);
        assertThat(hitCount("miss")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("findById_L0miss加L1正命中_counter_L1_自增")
    void l1PositiveHit_incrementsL1Counter() throws Exception {
        Merchant m = newMerchant("M-1");
        when(ops.get("catalog:merchant:M-1")).thenReturn(mapper.writeValueAsString(m));

        service.findById("M-1");

        assertThat(hitCount("L0")).isEqualTo(0.0);
        assertThat(hitCount("L1")).isEqualTo(1.0);
        assertThat(hitCount("L2")).isEqualTo(0.0);
        assertThat(hitCount("miss")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("findById_L0miss加L1负命中_counter_L1_自增")
    void l1NegativeHit_incrementsL1Counter() {
        when(ops.get("catalog:merchant:M-NONE")).thenReturn("{\"__null__\":true}");

        service.findById("M-NONE");

        assertThat(hitCount("L0")).isEqualTo(0.0);
        assertThat(hitCount("L1")).isEqualTo(1.0);
        assertThat(hitCount("L2")).isEqualTo(0.0);
        assertThat(hitCount("miss")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("findById_L0miss加L1miss加L2命中_counter_L2_自增")
    void l2Hit_incrementsL2Counter() {
        Merchant m = newMerchant("M-1");
        when(ops.get(anyString())).thenReturn(null);
        when(repo.findById("M-1")).thenReturn(Optional.of(m));

        service.findById("M-1");

        assertThat(hitCount("L0")).isEqualTo(0.0);
        assertThat(hitCount("L1")).isEqualTo(0.0);
        assertThat(hitCount("L2")).isEqualTo(1.0);
        assertThat(hitCount("miss")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("findById_L0miss加L1miss加L2miss_counter_miss_自增")
    void allMiss_incrementsMissCounter() {
        when(ops.get(anyString())).thenReturn(null);
        when(repo.findById("M-X")).thenReturn(Optional.empty());

        service.findById("M-X");

        assertThat(hitCount("L0")).isEqualTo(0.0);
        assertThat(hitCount("L1")).isEqualTo(0.0);
        assertThat(hitCount("L2")).isEqualTo(0.0);
        assertThat(hitCount("miss")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("findById_空id_不打任何 cache_hit 标签")
    void blankId_doesNotIncrementAnyCounter() {
        service.findById(null);
        service.findById("");
        service.findById("   ");

        assertThat(hitCount("L0")).isEqualTo(0.0);
        assertThat(hitCount("L1")).isEqualTo(0.0);
        assertThat(hitCount("L2")).isEqualTo(0.0);
        assertThat(hitCount("miss")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("findById_L1读失败降级L2_命中_仍按_L2_自增")
    void l1ReadFails_fallbackL2Hit_incrementsL2Counter() {
        Merchant m = newMerchant("M-1");
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));
        when(repo.findById("M-1")).thenReturn(Optional.of(m));

        service.findById("M-1");

        // L1 读失败被 safeRedisGet 兜底成 null → 跟 L1 miss 一样走到 L2 → counter L2
        assertThat(hitCount("L2")).isEqualTo(1.0);
        assertThat(hitCount("L1")).isEqualTo(0.0);
    }
}
