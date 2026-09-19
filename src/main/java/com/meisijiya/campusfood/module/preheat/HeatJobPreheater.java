package com.meisijiya.campusfood.module.preheat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.meisijiya.campusfood.module.preheat.assembler.CatalogHierarchyAssembler;
import com.meisijiya.campusfood.module.preheat.assembler.MerchantCatalog;
import com.meisijiya.campusfood.module.preheat.assembler.Zone;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantHeatCalculator;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

/**
 * 凌晨预热任务(F-4)— 每天 3 点从 MySQL 算热度、拼层级 JSON、按 zone 写 Redis 分片、
 * 写极热商户 SET、回写单 merchant L1 cache 与商户 heat_score。
 *
 * <h2>触发方式</h2>
 * <ul>
 *   <li>自动:{@link #run()} 由 {@link Scheduled @Scheduled(cron="0 0 3 * * ?")} 在凌晨 3 点触发</li>
 *   <li>手动:{@link #preheat()} 由 {@code PreheatAdminController#trigger} 在 dev profile 调用</li>
 * </ul>
 *
 * <h2>流程(单次 preheat 全量一次 findAll + 一次 scores,避免 N+1)</h2>
 * <ol>
 *   <li>{@link MerchantRepository#findAll()} 取全量 merchants(1 次查询)</li>
 *   <li>{@link MerchantHeatCalculator#scores(List)} 在传入 merchants 上算 heat score(零额外查询)</li>
 *   <li>{@link MerchantHeatCalculator#topN(List, int)} 取 Top N(零额外查询)</li>
 *   <li>{@link MerchantRepository#findAllById(Iterable)} 批量回写 heat_score(1 次 SELECT)</li>
 *   <li>{@link MerchantRepository#saveAll(Iterable)} 批量更新(1 次 UPDATE)</li>
 *   <li>{@link CatalogHierarchyAssembler#assemble(List)} 嵌套</li>
 *   <li>遍历 zones → 每个 zone 调 {@link RedisShardedWriter#writeZoneCatalog(String, MerchantCatalog)}</li>
 *   <li>{@link RedisShardedWriter#writeHotMerchants(Set)}</li>
 *   <li>逐 merchant {@link RedisShardedWriter#writeMerchantCache(String, Merchant)} 回写单 cache</li>
 * </ol>
 *
 * @author meisijiya
 */
@Component
class HeatJobPreheater {

    private static final Logger log = LoggerFactory.getLogger(HeatJobPreheater.class);

    /** 极热商户 SET 容量。 */
    static final int HOT_TOP_N = 10;

    private final MerchantRepository merchants;
    private final MerchantHeatCalculator heatCalculator;
    private final CatalogHierarchyAssembler assembler;
    private final RedisShardedWriter writer;

    HeatJobPreheater(
            MerchantRepository merchants,
            MerchantHeatCalculator heatCalculator,
            CatalogHierarchyAssembler assembler,
            RedisShardedWriter writer) {
        this.merchants = merchants;
        this.heatCalculator = heatCalculator;
        this.assembler = assembler;
        this.writer = writer;
    }

    /**
     * 凌晨 3 点 cron 触发(Spring 6 字段格式:秒 分 时 日 月 周;{@code "0 0 3 * * ?"} = 秒=0 分=0 时=3 *)。
     *
     * <p>由 {@code CampusFoodApplication#@EnableScheduling} 激活。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void run() {
        log.info("HeatJobPreheater scheduled run start");
        preheat();
    }

    /**
     * 公开预热入口(供 {@code PreheatAdminController} 手动触发)。
     *
     * @return 摘要:时间戳 + 商家数 + zone 数 + 极热商户数 + 耗时 ms
     */
    PreheatSummary preheat() {
        Instant start = Instant.now();
        log.info("HeatJobPreheater preheat start at={}", start);

        // 1. 全量 merchants(1 次 SELECT)
        List<Merchant> allMerchants = merchants.findAll();
        log.info("HeatJobPreheater loaded {} merchants", allMerchants.size());

        // 2. 算 heat score(无额外查询)
        Map<String, Double> scores = heatCalculator.scores(allMerchants);
        log.info("HeatJobPreheater computed scores for {} merchants", scores.size());

        // 3. 回写 heat_score 持久化(单 SELECT + 单 UPDATE 批量)
        persistHeatScores(scores);

        // 4. 嵌套
        MerchantCatalog catalog = assembler.assemble(allMerchants);

        // 5. 写 zone 分片
        int zoneCount = 0;
        if (catalog.getZones() != null) {
            for (Zone zone : catalog.getZones()) {
                if (zone == null || zone.getZoneId() == null || zone.getZoneId().isBlank()) {
                    continue;
                }
                MerchantCatalog zoneCatalog = new MerchantCatalog();
                zoneCatalog.setZones(List.of(zone));
                writer.writeZoneCatalog(zone.getZoneId(), zoneCatalog);
                zoneCount++;
            }
        }

        // 6. 写 hot merchants
        List<Merchant> hotList = heatCalculator.topN(allMerchants, HOT_TOP_N);
        Set<String> hotIds = new LinkedHashSet<>();
        for (Merchant m : hotList) {
            hotIds.add(m.getId());
        }
        writer.writeHotMerchants(hotIds);

        // 7. 回写单 merchant L1 cache(便于白天 L1 hit;F-5 Caffeine L0 接入后会减少 Redis 访问)
        for (Merchant m : allMerchants) {
            if (m.getId() != null && !m.getId().isBlank()) {
                writer.writeMerchantCache(m.getId(), m);
            }
        }

        Instant end = Instant.now();
        long elapsedMs = end.toEpochMilli() - start.toEpochMilli();
        PreheatSummary summary = new PreheatSummary(end, scores.size(), zoneCount, hotIds.size(), elapsedMs);
        log.info("HeatJobPreheater preheat done merchants={} zones={} hotMerchants={} elapsedMs={}",
                scores.size(), zoneCount, hotIds.size(), elapsedMs);
        return summary;
    }

    /**
     * 回写 {@code heat_score} 持久化:保证下次 cron 起点与 L1 一致性。
     *
     * <p>用 {@link MerchantRepository#findAllById} 批量 SELECT(避免 N 次 findById),
     * 改 score 后 {@link MerchantRepository#saveAll} 批量 UPDATE。
     */
    private void persistHeatScores(Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return;
        }
        List<Merchant> existing = merchants.findAllById(scores.keySet());
        if (existing.isEmpty()) {
            return;
        }
        List<Merchant> updated = new ArrayList<>(existing.size());
        for (Merchant m : existing) {
            Double s = scores.get(m.getId());
            if (s != null) {
                m.setHeatScore(s);
                updated.add(m);
            }
        }
        if (!updated.isEmpty()) {
            merchants.saveAll(updated);
        }
    }

    /**
     * 预热摘要 record(供 Controller 响应 JSON)。
     *
     * @param timestamp     完成时间戳
     * @param merchantCount 本次参与计算的 merchant 总数
     * @param zoneCount     写入的 zone 分片数
     * @param hotCount      写入 catalog:hot:merchants 的商户 ID 数(<= HOT_TOP_N)
     * @param elapsedMs     整次预热耗时
     */
    record PreheatSummary(Instant timestamp, int merchantCount, int zoneCount, int hotCount, long elapsedMs) {}
}