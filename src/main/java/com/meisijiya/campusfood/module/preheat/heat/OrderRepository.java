package com.meisijiya.campusfood.module.preheat.heat;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 订单 Repository(F-4)— 提供按商户 ID 计数的派生查询,供 {@link MerchantHeatCalculator} 计算热度。
 *
 * <p>F-4 阶段凌晨任务是单点全量计算;F-5 起加时间窗派生查询(如 {@code countByMerchantIdAndCreatedAtAfter})。
 *
 * @author meisijiya
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 统计某商户的订单总数。
     *
     * @param merchantId 商户 ID
     * @return 订单数量,无记录返回 0
     */
    long countByMerchantId(String merchantId);
}