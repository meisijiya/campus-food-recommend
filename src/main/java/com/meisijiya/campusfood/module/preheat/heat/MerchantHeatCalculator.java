package com.meisijiya.campusfood.module.preheat.heat;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * 商户热度计算器(F-4)— 从 MySQL 订单 / 点赞表读真实数据,按加权公式算出每个商户的热度分。
 *
 * <h2>公式</h2>
 * <pre>
 *   heatScore(merchant) = 0.6 * 订单数 + 0.4 * 点赞数
 * </pre>
 *
 * <p>权重与 {@code spec.md §4.7} 一致,作为凌晨 {@code HeatJobPreheater} 与白天
 * {@code MerchantQueryService} 的输入源。{@code 0.6 / 0.4} 权重比体现
 * "下单是更强的行为信号"的业务判断(F-3 评分降级也复用同一假设)。
 *
 * <h2>性能取舍</h2>
 * 提供两组 API:
 * <ul>
 *   <li>{@link #scores()} / {@link #topN(int)} — 内部调一次 {@code findAll()}(无参入口,简洁但 N+1)</li>
 *   <li>{@link #scores(List)} / {@link #topN(List, int)} — 接收 caller 传入的 merchants,
 *       适用于 {@code HeatJobPreheater} 这种已经持有 {@code findAll()} 结果的场景,避免重复查询</li>
 * </ul>
 *
 * <p>当前实现走 {@code N × 2 query} 单点 count,未做单次 SQL 聚合;上线前若 merchant 量级 > 10k,
 * 需替换为 {@code SELECT merchant_id, COUNT(*) GROUP BY} 的聚合查询。每个查询走
 * {@code idx_orders_merchant} / {@code idx_likes_merchant} 索引,数量在 demo 范围内无瓶颈。
 *
 * @author meisijiya
 */
@Component
public class MerchantHeatCalculator {

    /** 订单权重。 */
    static final double ORDER_WEIGHT = 0.6;

    /** 点赞权重。 */
    static final double LIKE_WEIGHT = 0.4;

    private final OrderRepository orders;
    private final LikeRepository likes;
    private final MerchantRepository merchants;

    MerchantHeatCalculator(OrderRepository orders, LikeRepository likes, MerchantRepository merchants) {
        this.orders = orders;
        this.likes = likes;
        this.merchants = merchants;
    }

    /**
     * 全量热度分(无参入口):遍历 {@link MerchantRepository#findAll()},按加权公式计算每个商户热度。
     *
     * <p>无任何订单 / 点赞的商户也会出现在结果中,默认 {@code 0.0},便于上层按热度降序统一处理。
     *
     * @return merchantId → heatScore 的不可变快照;key 集合 = 全量 merchant 集合
     */
    public Map<String, Double> scores() {
        return scores(merchants.findAll());
    }

    /**
     * 全量热度分(参数化入口):对 caller 给的 merchants 列表算 score,不触发额外 {@code findAll()}。
     *
     * <p>适用于 {@code HeatJobPreheater} 这种已持有 {@code findAll()} 结果的场景,避免重复扫描。
     *
     * @param merchantList 待计算 merchants(可空集合)
     * @return merchantId → heatScore;空集合入参返空 Map
     */
    public Map<String, Double> scores(List<Merchant> merchantList) {
        Map<String, Double> result = new HashMap<>();
        if (merchantList == null || merchantList.isEmpty()) {
            return result;
        }
        for (Merchant m : merchantList) {
            long orderCount = orders.countByMerchantId(m.getId());
            long likeCount = likes.countByMerchantId(m.getId());
            double score = ORDER_WEIGHT * orderCount + LIKE_WEIGHT * likeCount;
            result.put(m.getId(), score);
        }
        return result;
    }

    /**
     * 热度 Top N(无参入口):按降序;平手时按 merchantId 升序,保证多 JVM / 多轮跑结果稳定。
     *
     * @param n 期望返回的元素数量上限;{@code n <= 0} 返回空列表;{@code n > merchant 数} 返回全量
     * @return 排序后的商户列表,长度 == {@code min(n, merchants.size())}
     */
    public List<Merchant> topN(int n) {
        if (n <= 0) {
            return List.of();
        }
        Map<String, Double> scoreMap = scores();
        Comparator<Merchant> byScoreDescThenIdAsc = Comparator
                .comparing((Merchant m) -> scoreMap.getOrDefault(m.getId(), 0.0)).reversed()
                .thenComparing(Merchant::getId);
        return merchants.findAll().stream()
                .sorted(byScoreDescThenIdAsc)
                .limit(n)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 热度 Top N(参数化入口):对 caller 给的 merchants 列表排序取 Top N,不触发额外 {@code findAll()}。
     *
     * @param merchantList 待排序 merchants(可空集合)
     * @param n            期望返回的元素数量上限;{@code n <= 0} 返回空列表
     * @return 排序后的商户列表
     */
    public List<Merchant> topN(List<Merchant> merchantList, int n) {
        if (n <= 0 || merchantList == null || merchantList.isEmpty()) {
            return List.of();
        }
        Map<String, Double> scoreMap = scores(merchantList);
        Comparator<Merchant> byScoreDescThenIdAsc = Comparator
                .comparing((Merchant m) -> scoreMap.getOrDefault(m.getId(), 0.0)).reversed()
                .thenComparing(Merchant::getId);
        return merchantList.stream()
                .sorted(byScoreDescThenIdAsc)
                .limit(n)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 单商户热度(按相同加权公式)。商户不存在返回 {@code null},不抛异常,
     * 上层按 null 判定即可。
     *
     * @param merchantId 商户 ID
     * @return 该商户的热度分;{@code null} = 商户不存在
     */
    public Double scoreOf(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return null;
        }
        Merchant m = merchants.findById(merchantId).orElse(null);
        if (m == null) {
            return null;
        }
        long orderCount = orders.countByMerchantId(merchantId);
        long likeCount = likes.countByMerchantId(merchantId);
        return ORDER_WEIGHT * orderCount + LIKE_WEIGHT * likeCount;
    }
}