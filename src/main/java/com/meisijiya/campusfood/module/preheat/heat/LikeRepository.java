package com.meisijiya.campusfood.module.preheat.heat;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 点赞 Repository(F-4)— 提供按商户 ID 计数的派生查询,供 {@link MerchantHeatCalculator} 计算热度。
 *
 * <p>F-5 起 {@code likes} 表由 RabbitMQ 异步落库写入,本 Repository 仍供凌晨任务只读。
 *
 * @author meisijiya
 */
public interface LikeRepository extends JpaRepository<Like, Long> {

    /**
     * 统计某商户的点赞总数。
     *
     * @param merchantId 商户 ID
     * @return 点赞数量,无记录返回 0
     */
    long countByMerchantId(String merchantId);
}