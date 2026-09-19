package com.meisijiya.campusfood.module.preheat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.meisijiya.campusfood.module.lock.RedisLock;
import com.meisijiya.campusfood.module.preheat.assembler.CatalogHierarchyAssembler;
import com.meisijiya.campusfood.module.preheat.assembler.Cuisine;
import com.meisijiya.campusfood.module.preheat.assembler.MerchantCatalog;
import com.meisijiya.campusfood.module.preheat.assembler.Zone;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantHeatCalculator;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

/**
 * HeatJobPreheater 单元测试(F-4 acceptance #11 衍生):凌晨预热任务的编排逻辑验证。
 *
 * <p>策略:Mockito mock 所有依赖,验证:
 * <ul>
 *   <li>调用顺序(findAll → scores → saveAll → assemble → writeZoneCatalog × N → topN → writeHotMerchants → writeMerchantCache × N)</li>
 *   <li>摘要字段(merchantCount / zoneCount / hotCount / elapsedMs >= 0)</li>
 *   <li>边界(空 merchants / 空 zones / null zones / blank zoneId)</li>
 *   <li>性能:HeatJobPreheater 不再依赖 calculator 的无参 API,避免重复 findAll</li>
 *   <li>F-8 集成:RedisLock mock 默认 tryLock=true(让 F-4 既有断言专注 preheat body 编排);
 *       锁竞争场景由 {@link HeatJobPreheaterLockTest} 覆盖。</li>
 * </ul>
 *
 * @author meisijiya
 */
class HeatJobPreheaterTest {

    private MerchantRepository merchants;
    private MerchantHeatCalculator heatCalculator;
    private CatalogHierarchyAssembler assembler;
    private RedisShardedWriter writer;
    private RedisLock redisLock;
    private HeatJobPreheater preheater;

    @BeforeEach
    void setUp() {
        merchants = mock(MerchantRepository.class);
        heatCalculator = mock(MerchantHeatCalculator.class);
        assembler = mock(CatalogHierarchyAssembler.class);
        writer = mock(RedisShardedWriter.class);
        redisLock = mock(RedisLock.class);
        // F-8 默认锁可用 — 既有 F-4 编排断言不关心锁语义
        when(redisLock.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(redisLock.release(anyString(), anyString())).thenReturn(true);
        preheater = new HeatJobPreheater(
                merchants, heatCalculator, assembler, writer, redisLock);
    }

    private static Merchant merchant(String id, String zoneId, String cuisineId) {
        return new Merchant(id, zoneId, cuisineId, "name-" + id, "", 0.0);
    }

    @Test
    @DisplayName("preheat_正常数据_按顺序调用并写入zones与hot")
    void preheat_normalData_invokesInOrder() {
        // given
        Merchant m1 = merchant("M-1", "Z-1", "C-1");
        Merchant m2 = merchant("M-2", "Z-1", "C-2");
        Merchant m3 = merchant("M-3", "Z-2", "C-3");
        when(merchants.findAll()).thenReturn(List.of(m1, m2, m3));
        when(merchants.findAllById(any())).thenReturn(List.of(m1, m2, m3));
        when(heatCalculator.scores(List.of(m1, m2, m3)))
                .thenReturn(Map.of("M-1", 1.0, "M-2", 2.0, "M-3", 3.0));

        MerchantCatalog catalog = new MerchantCatalog();
        Zone z1 = new Zone("Z-1", List.of(
                new Cuisine("C-1", List.of(m1)),
                new Cuisine("C-2", List.of(m2))
        ));
        Zone z2 = new Zone("Z-2", List.of(
                new Cuisine("C-3", List.of(m3))
        ));
        catalog.setZones(List.of(z1, z2));
        when(assembler.assemble(any())).thenReturn(catalog);

        when(heatCalculator.topN(List.of(m1, m2, m3), HeatJobPreheater.HOT_TOP_N))
                .thenReturn(List.of(m3, m2));

        // when
        HeatJobPreheater.PreheatSummary summary = preheater.preheat();

        // then
        assertThat(summary.merchantCount()).isEqualTo(3);
        assertThat(summary.zoneCount()).isEqualTo(2);
        assertThat(summary.hotCount()).isEqualTo(2);
        assertThat(summary.elapsedMs()).isGreaterThanOrEqualTo(0L);

        // 调用顺序验证
        org.mockito.InOrder order = inOrder(merchants, heatCalculator, assembler, writer);
        order.verify(merchants).findAll();
        order.verify(heatCalculator).scores(List.of(m1, m2, m3));
        order.verify(merchants).findAllById(any());
        order.verify(merchants).saveAll(any());
        order.verify(assembler).assemble(any());
        order.verify(writer).writeZoneCatalog(eq("Z-1"), any(MerchantCatalog.class));
        order.verify(writer).writeZoneCatalog(eq("Z-2"), any(MerchantCatalog.class));
        order.verify(heatCalculator).topN(List.of(m1, m2, m3), HeatJobPreheater.HOT_TOP_N);
        order.verify(writer).writeHotMerchants(any());
        // 单 merchant cache 回填:N 次 writeMerchantCache
        order.verify(writer, times(3)).writeMerchantCache(anyString(), any());
    }

    @Test
    @DisplayName("preheat_空数据_不写zone且hotCount=0")
    void preheat_emptyData_noZones_noHot() {
        // given
        when(merchants.findAll()).thenReturn(List.of());
        when(merchants.findAllById(any())).thenReturn(List.of());
        when(heatCalculator.scores(List.of())).thenReturn(Map.of());
        MerchantCatalog emptyCatalog = new MerchantCatalog();
        emptyCatalog.setZones(List.of());
        when(assembler.assemble(any())).thenReturn(emptyCatalog);
        when(heatCalculator.topN(List.of(), HeatJobPreheater.HOT_TOP_N)).thenReturn(List.of());

        // when
        HeatJobPreheater.PreheatSummary summary = preheater.preheat();

        // then
        assertThat(summary.merchantCount()).isZero();
        assertThat(summary.zoneCount()).isZero();
        assertThat(summary.hotCount()).isZero();
        verify(writer, never()).writeZoneCatalog(anyString(), any());
        verify(writer).writeHotMerchants(any());  // 空集也写(key 存在契约)
        verify(writer, never()).writeMerchantCache(anyString(), any());
    }

    @Test
    @DisplayName("preheat_nullZones_不抛异常且zoneCount=0")
    void preheat_nullZones_safe() {
        // given
        Merchant m1 = merchant("M-1", "Z-1", "C-1");
        when(merchants.findAll()).thenReturn(List.of(m1));
        when(merchants.findAllById(any())).thenReturn(List.of(m1));
        when(heatCalculator.scores(List.of(m1))).thenReturn(Map.of("M-1", 1.0));
        MerchantCatalog nullZonesCatalog = new MerchantCatalog();
        nullZonesCatalog.setZones(null);
        when(assembler.assemble(any())).thenReturn(nullZonesCatalog);
        when(heatCalculator.topN(List.of(m1), HeatJobPreheater.HOT_TOP_N)).thenReturn(List.of(m1));

        // when
        HeatJobPreheater.PreheatSummary summary = preheater.preheat();

        // then
        assertThat(summary.zoneCount()).isZero();
        assertThat(summary.hotCount()).isEqualTo(1);
        verify(writer, never()).writeZoneCatalog(anyString(), any());
        verify(writer).writeMerchantCache(eq("M-1"), eq(m1));
    }

    @Test
    @DisplayName("preheat_zoneId空白_跳过该zone")
    void preheat_blankZoneId_skips() {
        // given
        Merchant m1 = merchant("M-1", "", "C-1");
        when(merchants.findAll()).thenReturn(List.of(m1));
        when(merchants.findAllById(any())).thenReturn(List.of(m1));
        when(heatCalculator.scores(List.of(m1))).thenReturn(Map.of("M-1", 1.0));
        Zone blankZone = new Zone("", List.of(new Cuisine("C-1", List.of(m1))));
        MerchantCatalog catalog = new MerchantCatalog();
        catalog.setZones(List.of(blankZone));
        when(assembler.assemble(any())).thenReturn(catalog);
        when(heatCalculator.topN(List.of(m1), HeatJobPreheater.HOT_TOP_N)).thenReturn(List.of(m1));

        // when
        HeatJobPreheater.PreheatSummary summary = preheater.preheat();

        // then
        assertThat(summary.zoneCount()).isZero();
        verify(writer, never()).writeZoneCatalog(anyString(), any());
    }

    @Test
    @DisplayName("preheat_回写heatScore_每个merchant都被持久化")
    void preheat_persistHeatScores_invokesSaveAll() {
        // given
        Merchant m1 = merchant("M-1", "Z-1", "C-1");
        Merchant m2 = merchant("M-2", "Z-1", "C-1");
        when(merchants.findAll()).thenReturn(List.of(m1, m2));
        when(merchants.findAllById(any())).thenReturn(List.of(m1, m2));
        when(heatCalculator.scores(List.of(m1, m2))).thenReturn(Map.of("M-1", 5.5, "M-2", 3.3));
        MerchantCatalog empty = new MerchantCatalog();
        empty.setZones(List.of());
        when(assembler.assemble(any())).thenReturn(empty);
        when(heatCalculator.topN(List.of(m1, m2), HeatJobPreheater.HOT_TOP_N)).thenReturn(List.of());

        // when
        preheater.preheat();

        // then — 用批量 findAllById 而非 N 次 findById(性能优化,verifier review finding)
        verify(merchants, times(1)).findAllById(any());
        verify(merchants, times(1)).saveAll(any());
        assertThat(m1.getHeatScore()).isEqualTo(5.5);
        assertThat(m2.getHeatScore()).isEqualTo(3.3);
    }

    @Test
    @DisplayName("preheat_不调用MerchantHeatCalculator无参API_避免重复findAll")
    void preheat_usesParameterizedApiOnly() {
        // 验证 verifier review finding:不能调用无参 scores() / topN(int),避免重复 findAll
        // given
        Merchant m1 = merchant("M-1", "Z-1", "C-1");
        when(merchants.findAll()).thenReturn(List.of(m1));
        when(merchants.findAllById(any())).thenReturn(List.of(m1));
        when(heatCalculator.scores(List.of(m1))).thenReturn(Map.of("M-1", 1.0));
        MerchantCatalog empty = new MerchantCatalog();
        empty.setZones(List.of());
        when(assembler.assemble(any())).thenReturn(empty);
        when(heatCalculator.topN(List.of(m1), HeatJobPreheater.HOT_TOP_N)).thenReturn(List.of(m1));

        // when
        preheater.preheat();

        // then
        verify(heatCalculator, never()).scores();  // 永远不要调无参版本
        verify(heatCalculator, never()).topN(anyInt());  // 永远不要调无参版本
    }
}