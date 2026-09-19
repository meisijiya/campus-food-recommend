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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meisijiya.campusfood.module.preheat.RedisShardedWriter;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

/**
 * {@link MerchantQueryService} 单元测试(F-4 + review fix)— 覆盖 L1→L2 严格降级链路 + zone 查询。
 *
 * <h2>F-4 review fix 关联测试</h2>
 * <ul>
 *   <li>L1 正缓存命中:不调 MySQL,直接返 Merchant</li>
 *   <li>L1 负缓存命中:不调 MySQL,直接返 Optional.empty()</li>
 *   <li>L1 miss + L2 hit:写 L1 回填,返 Merchant</li>
 *   <li>L1 miss + L2 miss:写 L1 负缓存,返 Optional.empty()</li>
 * </ul>
 *
 * <p>纯 Mockito + AssertJ,不依赖 Spring 上下文,保证单测运行时间 < 100ms。
 *
 * @author meisijiya
 */
class MerchantQueryServiceTest {

    private final MerchantRepository repo = mock(MerchantRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedisShardedWriter writer = mock(RedisShardedWriter.class);
    private final MerchantQueryService service = new MerchantQueryService(repo, redis, mapper, writer);

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

    // ---------- F-4 review fix: L1→L2 严格降级 ----------

    @Test
    @DisplayName("findById_L1正缓存命中_不调MySQL直接返")
    void findById_l1Hit_returnsMerchant() throws Exception {
        Merchant m = newMerchant("M-1");
        String cached = mapper.writeValueAsString(m);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("catalog:merchant:M-1")).thenReturn(cached);

        Optional<Merchant> result = service.findById("M-1");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("M-1");
        // L1 命中不能调 MySQL
        verify(repo, never()).findById(anyString());
        // L1 命中不能回填
        verify(writer, never()).writeMerchantCache(anyString(), any());
    }

    @Test
    @DisplayName("findById_L1负缓存命中_不调MySQL直接返空")
    void findById_l1NegativeHit_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("catalog:merchant:M-NONE")).thenReturn("{\"__null__\":true}");

        Optional<Merchant> result = service.findById("M-NONE");

        assertThat(result).isEmpty();
        verify(repo, never()).findById(anyString());
        verify(writer, never()).writeMerchantCache(anyString(), any());
    }

    @Test
    @DisplayName("findById_L1miss加L2hit_写L1回填并返")
    void findById_l1Miss_l2Hit_writesCacheAndReturns() {
        Merchant m = newMerchant("M-1");
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("catalog:merchant:M-1")).thenReturn(null);
        when(repo.findById("M-1")).thenReturn(Optional.of(m));

        Optional<Merchant> result = service.findById("M-1");

        assertThat(result).isPresent();
        verify(repo, times(1)).findById("M-1");
        // 写 L1 回填(命中数据,非 null)
        verify(writer, times(1)).writeMerchantCache(eq("M-1"), eq(m));
    }

    @Test
    @DisplayName("findById_L1miss加L2miss_写L1负缓存并返空")
    void findById_l1Miss_l2Miss_writesNegativeCacheAndReturnsEmpty() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("catalog:merchant:M-X")).thenReturn(null);
        when(repo.findById("M-X")).thenReturn(Optional.empty());

        Optional<Merchant> result = service.findById("M-X");

        assertThat(result).isEmpty();
        verify(repo, times(1)).findById("M-X");
        // 写 L1 负缓存(null marker)
        verify(writer, times(1)).writeMerchantCache(eq("M-X"), eq(null));
    }

    @Test
    @DisplayName("findById_空或null_id_返空且不查Redis也不查MySQL")
    void findById_blankId_returnsEmpty() {
        assertThat(service.findById(null)).isEmpty();
        assertThat(service.findById("")).isEmpty();
        assertThat(service.findById("   ")).isEmpty();
        verify(redis, never()).opsForValue();
        verify(repo, never()).findById(anyString());
    }

    @Test
    @DisplayName("findById_L1读失败_降级到L2且不抛")
    void findById_l1ReadFails_fallbackToL2() {
        Merchant m = newMerchant("M-1");
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RuntimeException("Redis down"));
        when(repo.findById("M-1")).thenReturn(Optional.of(m));

        Optional<Merchant> result = service.findById("M-1");

        assertThat(result).isPresent();
        // L2 命中后写 L1 回填
        verify(writer, times(1)).writeMerchantCache(eq("M-1"), eq(m));
    }

    // ---------- zone 查询(F-4 acceptance #7 衍生) ----------

    @Test
    @DisplayName("findByZoneId_有商户_返回完整列表")
    void findByZoneId_returnsList() {
        Merchant m1 = newMerchant("M-1");
        Merchant m2 = newMerchant("M-2");
        m2.setZoneId("Z-1");
        when(repo.findByZoneId("Z-1")).thenReturn(List.of(m1, m2));

        List<Merchant> result = service.findByZoneId("Z-1");

        assertThat(result).hasSize(2)
                .extracting(Merchant::getId)
                .containsExactly("M-1", "M-2");
    }

    @Test
    @DisplayName("findByZoneId_空zoneId_返空列表")
    void findByZoneId_blankZoneId_returnsEmpty() {
        List<Merchant> result = service.findByZoneId("");
        assertThat(result).isEmpty();
        verify(repo, never()).findByZoneId(anyString());
    }

    @Test
    @DisplayName("findByZoneId_无商户_返回空列表")
    void findByZoneId_empty_returnsEmptyList() {
        when(repo.findByZoneId("Z-X")).thenReturn(List.of());

        List<Merchant> result = service.findByZoneId("Z-X");

        assertThat(result).isEmpty();
    }
}