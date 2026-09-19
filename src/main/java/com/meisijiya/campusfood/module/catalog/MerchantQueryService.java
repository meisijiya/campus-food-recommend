package com.meisijiya.campusfood.module.catalog;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

/**
 * 商户查询服务(F-4 L1→L2 中间态)— 暴露给 {@link MerchantController} 的商户读模型。
 *
 * <h2>F-4 阶段定位</h2>
 * 当前只走 L2 MySQL,跳过 L0/L1 缓存层;这是有意的中间态,目的是把"读路径稳定下来"
 * 后再让 F-5 一次性接入 Caffeine(L0)+ Redis(L1)+ MySQL(L2)三级降级,F-5 接入时本类
 * 只在方法体里加缓存包装,接口签名不变。
 *
 * <h2>F-5 升级占位</h2>
 * F-5 升级为 L0 → L1 → L2 严格降级时,本类将插入:
 * <ul>
 *   <li>Caffeine L0({@code Cache<String, Merchant>},per-merchant,几秒 TTL)</li>
 *   <li>Redis L1({@code String merchant:<id> → JSON},per-merchant,分钟级 TTL)</li>
 *   <li>MySQL L2(沿用本类当前方法)</li>
 * </ul>
 * 升级时 {@link MerchantController} 与测试不受影响。
 *
 * <p>类 Javadoc 显式声明 F-5 升级路径,避免后人误把 Redis 单 merchant cache 提前塞进
 * F-4 导致 F-5 改造出现双套缓存实现。
 *
 * @author meisijiya
 */
@Service
class MerchantQueryService {

    private static final Logger log = LoggerFactory.getLogger(MerchantQueryService.class);

    private final MerchantRepository repository;

    MerchantQueryService(MerchantRepository repository) {
        this.repository = repository;
    }

    /**
     * 按主键查单商户(F-4 中间态:直接走 L2 MySQL)。
     *
     * <p>F-5 升级为:
     * <pre>
     *   Caffeine.getIfPresent(merchantId)  → 命中即返
     *      ↓ miss
     *   redisTemplate.opsForValue().get("merchant:" + merchantId)  → 命中写回 Caffeine 并返
     *      ↓ miss
     *   repository.findById(merchantId)  → 写回 Caffeine + Redis 并返
     * </pre>
     *
     * @param merchantId 商户主键(业务方提供,非自增)
     * @return Optional 包装的商户;空 = 不存在(由 Controller 决定抛 404 还是返空)
     */
    Optional<Merchant> findById(String merchantId) {
        Optional<Merchant> result = repository.findById(merchantId);
        if (result.isPresent()) {
            log.info("MerchantQueryService L2 hit, merchantId={}", merchantId);
        } else {
            log.warn("MerchantQueryService L2 miss, merchantId={}", merchantId);
        }
        return result;
    }

    /**
     * 按 zoneId 列出商户(F-4 中间态:直接走 L2 MySQL)。
     *
     * <p>F-5 升级为 Redis 区域级 cache key({@code merchants:zone:<zoneId> → JSON[List]},
     * 分钟级 TTL)+ Caffeine 嵌套缓存(以 zoneId 为 key,值为商户列表)。F-4 阶段不预热
     * 此 key,由凌晨 {@code HeatJobPreheater} 写;白天首次读穿透 L1/L0 是预期行为。
     *
     * @param zoneId 校区 / 区域 ID
     * @return 该 zone 下商户列表(空集合 = 该 zone 无商户)
     */
    List<Merchant> findByZoneId(String zoneId) {
        List<Merchant> result = repository.findByZoneId(zoneId);
        log.info("MerchantQueryService L2 zone query, zoneId={}, size={}", zoneId, result.size());
        return result;
    }
}