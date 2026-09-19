package com.meisijiya.campusfood.module.preheat.heat;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 商户 Repository(F-4)— 提供全量扫描与单点查询,供 {@link MerchantHeatCalculator} 使用。
 *
 * <p>对外接口尽量保持 Spring Data 默认命名;F-4 W3 新增 {@link #findByZoneId(String)}
 * 派生查询供 {@code MerchantQueryService} 的区域列表接口用。
 *
 * @author meisijiya
 */
public interface MerchantRepository extends JpaRepository<Merchant, String> {

    /**
     * 按 zoneId 列出商户(F-4 W3)— Spring Data 派生查询,无需写 {@code @Query}。
     *
     * <p>F-5 升级 L0/L1 缓存层时,本方法仍是 L2 真值源。
     *
     * @param zoneId 校区 / 区域 ID
     * @return 该 zone 下商户列表(空集合 = 该 zone 无商户)
     */
    List<Merchant> findByZoneId(String zoneId);
}