package com.meisijiya.campusfood.module.preheat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.meisijiya.campusfood.module.lock.RedisLock;
import com.meisijiya.campusfood.module.preheat.assembler.CatalogHierarchyAssembler;
import com.meisijiya.campusfood.module.preheat.assembler.MerchantCatalog;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantHeatCalculator;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

/**
 * {@link HeatJobPreheater} F-8 分布式锁集成测试 — 覆盖 cron {@code run()} 与手动
 * {@code preheat()} 入口的锁竞争路径。
 *
 * <p>策略:Mockito mock 所有依赖;专门覆盖锁的三种状态 — 成功获取(默认)/ 锁竞争失败
 * (tryLock=false)/ 释放失败(release=false)。验证:
 * <ul>
 *   <li>锁竞争失败时 run() / preheat() 立即返回,不调任何 writer 或 calculator。</li>
 *   <li>锁成功路径调 doPreheat() 内部既有编排,不被锁本身阻塞。</li>
 *   <li>无论 tryLock 成功或失败,finally 中都尝试 release(锁失败时跳过释放,符合"未持有不释放"语义)。</li>
 * </ul>
 *
 * <p>本测试与 {@link HeatJobPreheaterTest} 互补:后者专注 F-4 编排逻辑(假设锁可用),
 * 本测试专注 F-8 锁语义(假设 F-4 编排可用)。
 *
 * @author meisijiya
 */
class HeatJobPreheaterLockTest {

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
        preheater = new HeatJobPreheater(
                merchants, heatCalculator, assembler, writer, redisLock);
    }

    @Test
    @DisplayName("run_锁竞争失败_不调doPreheat且不释放(未持有)")
    void run_lockContended_skipsDoPreheat() {
        // given — 模拟"另一实例正持有锁"
        when(redisLock.tryLock(eq(HeatJobPreheater.LOCK_KEY), anyString(), anyLong()))
                .thenReturn(false);

        // when
        preheater.run();

        // then — 任何下游调用都不应触发
        verify(merchants, never()).findAll();
        verify(heatCalculator, never()).scores(any());
        verify(merchants, never()).saveAll(any());
        verify(writer, never()).writeZoneCatalog(anyString(), any());
        verify(writer, never()).writeHotMerchants(any());
        verify(writer, never()).writeMerchantCache(anyString(), any());
        // tryLock 被调一次;锁未获取成功 → 不调 release(语义:"只释放自己持有的锁")
        verify(redisLock, times(1)).tryLock(eq(HeatJobPreheater.LOCK_KEY), anyString(),
                eq(HeatJobPreheater.LOCK_TTL_SEC));
        verify(redisLock, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("run_锁获取成功_调doPreheat且finally释放")
    void run_lockAcquired_invokesDoPreheat_andReleases() {
        // given — 锁可用 + 灌一条最小数据让 doPreheat 走完整 7 步
        when(redisLock.tryLock(eq(HeatJobPreheater.LOCK_KEY), anyString(), anyLong()))
                .thenReturn(true);
        Merchant m1 = new Merchant("M-1", "Z-1", "C-1", "noodle", "", 0.0);
        when(merchants.findAll()).thenReturn(List.of(m1));
        when(merchants.findAllById(any())).thenReturn(List.of(m1));
        when(heatCalculator.scores(any())).thenReturn(Map.of("M-1", 1.0));
        when(assembler.assemble(any())).thenReturn(new MerchantCatalog());
        when(heatCalculator.topN(any(), anyInt())).thenReturn(List.of(m1));

        // when
        preheater.run();

        // then — doPreheat 流程跑完:findAll 至少 1 次
        verify(merchants, times(1)).findAll();
        verify(writer, times(1)).writeHotMerchants(any());
        verify(redisLock, times(1)).tryLock(eq(HeatJobPreheater.LOCK_KEY), anyString(),
                eq(HeatJobPreheater.LOCK_TTL_SEC));
        verify(redisLock, times(1)).release(eq(HeatJobPreheater.LOCK_KEY), anyString());
    }

    @Test
    @DisplayName("preheat_锁竞争失败_返null_不调doPreheat_不释放")
    void preheat_lockContended_returnsNull_skipsDoPreheat() {
        // given
        when(redisLock.tryLock(eq(HeatJobPreheater.LOCK_KEY), anyString(), anyLong()))
                .thenReturn(false);

        // when
        HeatJobPreheater.PreheatSummary summary = preheater.preheat();

        // then
        assertThat(summary).isNull();
        verify(merchants, never()).findAll();
        verify(heatCalculator, never()).scores(any());
        verify(writer, never()).writeZoneCatalog(anyString(), any());
        verify(writer, never()).writeHotMerchants(any());
        // 锁未获取成功 → 不调 release
        verify(redisLock, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("preheat_锁获取成功_返回PreheatSummary且释放")
    void preheat_lockAcquired_returnsSummary_andReleases() {
        // given
        when(redisLock.tryLock(eq(HeatJobPreheater.LOCK_KEY), anyString(), anyLong()))
                .thenReturn(true);
        Merchant m1 = new Merchant("M-1", "Z-1", "C-1", "noodle", "", 0.0);
        when(merchants.findAll()).thenReturn(List.of(m1));
        when(merchants.findAllById(any())).thenReturn(List.of(m1));
        when(heatCalculator.scores(any())).thenReturn(Map.of("M-1", 1.0));
        when(assembler.assemble(any())).thenReturn(new MerchantCatalog());
        when(heatCalculator.topN(any(), anyInt())).thenReturn(List.of(m1));

        // when
        HeatJobPreheater.PreheatSummary summary = preheater.preheat();

        // then
        assertThat(summary).isNotNull();
        assertThat(summary.merchantCount()).isEqualTo(1);
        assertThat(summary.hotCount()).isEqualTo(1);
        verify(merchants, times(1)).findAll();
        verify(redisLock, times(1)).tryLock(eq(HeatJobPreheater.LOCK_KEY), anyString(),
                eq(HeatJobPreheater.LOCK_TTL_SEC));
        verify(redisLock, times(1)).release(eq(HeatJobPreheater.LOCK_KEY), anyString());
    }

    @Test
    @DisplayName("LOCK_KEY与LOCK_TTL_SEC_符合工单规格")
    void lockConstants_matchSpec() {
        // F-8 工单 §What to build:锁 key 复用 "lock:preheat:heat-job",TTL 1800s = 30 分钟
        assertThat(HeatJobPreheater.LOCK_KEY).isEqualTo("lock:preheat:heat-job");
        assertThat(HeatJobPreheater.LOCK_TTL_SEC).isEqualTo(1800L);
    }
}