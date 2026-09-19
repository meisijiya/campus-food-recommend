package com.meisijiya.campusfood.module.preheat;

import java.time.Instant;
import java.util.ArrayList;
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
import com.meisijiya.campusfood.module.preheat.heat.LikeRepository;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantHeatCalculator;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;
import com.meisijiya.campusfood.module.preheat.heat.OrderRepository;

/**
 * 凌晨预热任务(F-4)— 每天 3 点从 MySQL 算热度、拼层级 JSON、按 zone 写 Redis 分片与极热商户 SET。
 *
 * <h2>触发方式</h2>
 * <ul>
 *   <li>自动:{@link #run()} 由 {@link Scheduled @Scheduled(cron="0 0 3 * * ?")} 在凌晨 3 点触发</li>
 *   <li>手动:{@link #preheat()} 由 {@code PreheatAdminController#trigger} 在 dev profile 调用</li>
 * </ul>
 *
 * <h2>流程</h2>
 * <ol>
 *   <li>{@link MerchantHeatCalculator#scores()} 全量热度(0.6 × 订单 + 0.4 × 点赞)</li>
 *   <li>{@link MerchantRepository#findAll()} 取全量 merchants</li>
 *   <li>回写 {@code heat_score} 持久化(保证白天读 L1 一致性 + 下次 cron 起点)</li>
 *   <li>{@link CatalogHierarchyAssembler#assemble(List)} 嵌套成 MerchantCatalog</li>
 *   <li>遍历 zones → 每个 zone 调 {@link RedisShardedWriter#writeZoneCatalog(String, MerchantCatalog)}</li>
 *   <li>{@link MerchantHeatCalculator#topN(int)} 取热度 Top N → {@link RedisShardedWriter#writeHotMerchants(Set)}</li>
 * </ol>
 *
 * <p>{@link OrderRepository} / {@link LikeRepository} 注入是为 F-5 扩展(F-5 凌晨任务会加时间窗)
 * 留口子;当前不直接使用,经 {@code heatCalculator} 间接消费。
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
            @SuppressWarnings("unused") OrderRepository orders,
            @SuppressWarnings("unused") LikeRepository likes,
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

        // 1. 全量热度
        Map<String, Double> scores = heatCalculator.scores();
        log.info("HeatJobPreheater computed scores for {} merchants", scores.size());

        // 2. 全量 merchants
        List<Merchant> allMerchants = merchants.findAll();

        // 3. 回写 heat_score 持久化
        persistHeatScores(scores);

        // 4. 嵌套
        MerchantCatalog catalog = assembler.assemble(allMerchants);

        // 5. 写 zone 分片
        int zoneCount = 0;
        if (catalog.zones() != null) {
            for (Zone zone : catalog.zones()) {
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
        List<Merchant> hotList = heatCalculator.topN(HOT_TOP_N);
        Set<String> hotIds = new LinkedHashSet<>();
        for (Merchant m : hotList) {
            hotIds.add(m.getId());
        }
        writer.writeHotMerchants(hotIds);

        Instant end = Instant.now();
        long elapsedMs = end.toEpochMilli() - start.toEpochMilli();
        PreheatSummary summary = new PreheatSummary(end, scores.size(), zoneCount, hotIds.size(), elapsedMs);
        log.info("HeatJobPreheater preheat done merchants={} zones={} hotMerchants={} elapsedMs={}",
                scores.size(), zoneCount, hotIds.size(), elapsedMs);
        return summary;
    }

    /**
     * 回写 {@code heat_score} 持久化(可选但推荐):保证下次 cron 起点与 L1 一致性。
     */
    private void persistHeatScores(Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return;
        }
        List<Merchant> updated = new ArrayList<>();
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            merchants.findById(e.getKey()).ifPresent(m -> {
                m.setHeatScore(e.getValue());
                updated.add(m);
            });
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