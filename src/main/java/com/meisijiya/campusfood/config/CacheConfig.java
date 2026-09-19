package com.meisijiya.campusfood.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;

/**
 * Caffeine L0 本地缓存配置(F-5)— 在 L1 Redis 与 L2 MySQL 之间再插一层进程内缓存,
 * 拦截对极热商户 / 热门区域的反复读,降低 Redis QPS 占用并提供 1 ms 级响应。
 *
 * <h2>两个 cache bean 的契约</h2>
 * <ul>
 *   <li>{@code merchantHotCache}:容量 10000,expireAfterWrite=5min;value 用
 *       {@link Optional}{@code <Merchant>} 表达"已确认不存在"的负缓存,避免 sentinel
 *       商户与真实业务数据混淆。</li>
 *   <li>{@code zoneCatalogCache}:容量 200,expireAfterWrite=10min;value 是
 *       {@link List}{@code <Merchant>},空集合代表该 zone 无商户(等同于负缓存)。</li>
 * </ul>
 *
 * <h2>L0 vs L1 序列化边界</h2>
 * <p>Caffeine 是 JVM-local 缓存,value 直接持 POJO 引用 — <strong>不要</strong>用 JSON
 * 序列化,序列化会增加 CPU 开销且丧失 Caffeine 的强类型优势;跨进程一致性靠 L1 Redis
 * 的 JSON 序列化保证。本配置只声明 cache bean,不做任何主动加载 — 数据回填由
 * {@code MerchantQueryService} 在 L1 miss → L2 命中后写回。
 *
 * <h2>不启用 Spring Cache 抽象</h2>
 * <p>本项目不引入 {@code @Cacheable} / {@code @EnableCaching} — 因为业务对 3 级降级
 * 的链路日志 / 负缓存写入 / L0 backfill 时机有精细控制需求,Spring Cache 抽象会吞掉
 * 这些细节。直接拿 {@link Cache} bean 注入到 service 写显式降级链路,语义更清晰。
 *
 * <p>不带 Lombok,字段无 / 全部为 {@code @Bean} 工厂方法,符合 F-1 起的项目惯例。
 *
 * @author meisijiya
 */
@Configuration
public class CacheConfig {

    /** {@code merchantHotCache} 容量上限(spec §5:极热商户 1 万)。 */
    static final int MERCHANT_HOT_MAX_SIZE = 10_000;

    /** {@code merchantHotCache} 写入后过期时间(spec §5:5 分钟)。 */
    static final Duration MERCHANT_HOT_TTL = Duration.ofMinutes(5);

    /** {@code zoneCatalogCache} 容量上限(spec §5:热门校区 200)。 */
    static final int ZONE_CATALOG_MAX_SIZE = 200;

    /** {@code zoneCatalogCache} 写入后过期时间(spec §5:10 分钟)。 */
    static final Duration ZONE_CATALOG_TTL = Duration.ofMinutes(10);

    /**
     * 单商户 L0 cache — 进程内 Caffeine,容量 10000,5 分钟写入过期。
     *
     * <p>value 用 {@code Optional<Merchant>}:正缓存写 {@code Optional.of(m)},负缓存写
     * {@code Optional.empty()};{@code getIfPresent(key) == null} 才是真 miss。这种设计
     * 让"已确认不存在"与"尚未查询"在 Caffeine 语义里彻底分开。
     *
     * @return 进程内 Caffeine 单商户 cache,bean name 必须为 {@code merchantHotCache} —
     *         {@code MerchantQueryService} 严格按 name 装配。
     */
    @Bean(name = "merchantHotCache")
    public Cache<String, Optional<Merchant>> merchantHotCache() {
        return Caffeine.newBuilder()
                .maximumSize(MERCHANT_HOT_MAX_SIZE)
                .expireAfterWrite(MERCHANT_HOT_TTL)
                .build();
    }

    /**
     * 校区目录 L0 cache — 进程内 Caffeine,容量 200,10 分钟写入过期。
     *
     * <p>value 为扁平化后的 {@code List<Merchant>}:L1 Redis 存的是 MerchantCatalog 三层
     * 树,F-5 在 L1 → L0 回填时做扁平化;空集合({@code List.of()})代表该 zone 确实无商户
     * — zone 维度永远有"答案"(空 = 空),不需要 Optional 包装。
     *
     * @return 进程内 Caffeine 校区目录 cache,bean name 必须为 {@code zoneCatalogCache}。
     */
    @Bean(name = "zoneCatalogCache")
    public Cache<String, List<Merchant>> zoneCatalogCache() {
        return Caffeine.newBuilder()
                .maximumSize(ZONE_CATALOG_MAX_SIZE)
                .expireAfterWrite(ZONE_CATALOG_TTL)
                .build();
    }
}