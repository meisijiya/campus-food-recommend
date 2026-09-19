package com.meisijiya.campusfood.config;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;

import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;

/**
 * Micrometer 业务指标注册中心(F-9 工程亮点)— 注册 4 个业务指标名 + 暴露静态 helper
 * 让 LikeService / RecommendService / MerchantQueryService / SessionService 安全打点。
 *
 * <h2>4 个业务指标</h2>
 * <ul>
 *   <li>{@code like_count_total} (Counter) — LikeService.like 成功路径;tag: endpoint</li>
 *   <li>{@code recommend_latency_seconds} (Timer) — RecommendService.recommend;tags: endpoint + hit_tier(mocks/dashscope/fallback)</li>
 *   <li>{@code cache_hit_ratio} (Gauge) — MerchantQueryService;tags: cache_name + hit_tier — W2 实现 callback</li>
 *   <li>{@code session_stage_distribution} (Gauge) — SessionService;tags: stage — W2 实现 callback</li>
 * </ul>
 *
 * <h2>6 个技术指标(由 Spring Boot Actuator 自动暴露)</h2>
 * <p>{@code tomcat_threads_busy} / {@code hikari_pool_active} / {@code redis_pool_active} /
 * {@code jvm_memory_used_bytes} / {@code gc_pause_seconds} /
 * {@code http_server_requests_seconds_count} — 不在本类注册,Spring Boot Actuator
 * 自带 binder 接好。<strong>W2</strong> 负责 {@code application.yml} 开启 prometheus 导出,
 * 本类不写配置。
 *
 * <h2>设计纪律</h2>
 * <ul>
 *   <li>Counter / Timer 直接 {@code builder().register(meterRegistry)} — 同一调用点多次注册
 *       自动复用,不会重复打点。</li>
 *   <li>Gauge 由 {@link AtomicLong} 持有数值,callback 写入 — 避免在业务线程做浮点运算,
 *       也让单测可直接 {@code gauge.set(...)} 验证。</li>
 *   <li>{@link #holderGauge(String, String[], AtomicLong)} 暴露给 W2 注册 callback。W2
 *       可以传 {@code (cacheHit.get(), cacheMiss.get()) → ratio} 等任意 {@link AtomicLong} 数值。</li>
 *   <li>不带 Lombok,字段全包私有 + 显式构造器注入(F-1 起的项目惯例)。</li>
 * </ul>
 *
 * @author meisijiya
 */
@Configuration
public class MicrometerConfig {

    /** {@code like_count_total} — tag {@code endpoint="like"}. */
    public static final String LIKE_COUNT = "like_count_total";

    /** {@code recommend_latency_seconds} — tags {@code endpoint} + {@code hit_tier}. */
    public static final String RECOMMEND_LATENCY = "recommend_latency_seconds";

    /** {@code cache_hit_ratio} — tags {@code cache_name} + {@code hit_tier}. */
    public static final String CACHE_HIT_RATIO = "cache_hit_ratio";

    /** {@code session_stage_distribution} — tag {@code stage}. */
    public static final String SESSION_STAGE_DISTRIBUTION = "session_stage_distribution";

    private final MeterRegistry meterRegistry;

    public MicrometerConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 注册 4 个业务指标的"占位 Meter" + 返回 helper 给业务方法调用。
     *
     * <p>Counter / Timer 走 {@code builder().register(meterRegistry)} 是 lazy 注册:
     * 只要 key 第一次自增 / record 时,该 meter 才会出现在 registry — 不需要也不应该
     * 在 PostConstruct 里手动 register 业务 Counter / Timer。
     *
     * <p>Gauge 需要在 PostConstruct 阶段注册 holder + 把 callback 写好;W2 拿到
     * {@link #holderGauge} 注入的 {@link AtomicLong} 引用,业务命中时 set。
     */
    @PostConstruct
    void registerPlaceholders() {
        // Counter / Timer 不在这里预注册 — Counter.builder / Timer.builder / registry.timer
        // 在调用点 first-write 时自动注册,避免冷启动有 0 值的死指标。

        // Gauge 占位:用 0 值的 AtomicLong 注册,等 W2 改业务侧写入真实数值。
        AtomicLong cacheHitRatioZero = new AtomicLong(0);
        Gauge.builder(CACHE_HIT_RATIO, cacheHitRatioZero, AtomicLong::doubleValue)
                .description("Cache hit ratio (0.0 ~ 1.0) — populated by MerchantQueryService (F-9 W2)")
                .tags("cache_name", "_placeholder", "hit_tier", "_placeholder")
                .register(meterRegistry);

        AtomicLong sessionStageZero = new AtomicLong(0);
        Gauge.builder(SESSION_STAGE_DISTRIBUTION, sessionStageZero, AtomicLong::doubleValue)
                .description("Session stage distribution count — populated by SessionService (F-9 W2)")
                .tags("stage", "_placeholder")
                .register(meterRegistry);
    }

    /** Counter helper — LikeService.like 成功路径调用。 */
    public Counter likeCounter(String endpoint) {
        return Counter.builder(LIKE_COUNT)
                .description("Total like successes (60s idempotent window passed)")
                .tag("endpoint", endpoint)
                .register(meterRegistry);
    }

    /** Timer helper — RecommendService.recommend 每次调用耗时记录到对应 tier。 */
    public Timer recommendTimer(String endpoint, String hitTier) {
        return Timer.builder(RECOMMEND_LATENCY)
                .description("Recommend latency in seconds; hit_tier ∈ {mock, dashscope, fallback}")
                .tag("endpoint", endpoint)
                .tag("hit_tier", hitTier)
                .register(meterRegistry);
    }

    /**
     * Gauge helper — 给 W2(MerchantQueryService / SessionService)使用:
     * 业务侧拿到 {@link AtomicLong} 引用,在 cache hit / session stage 变化时
     * 直接 {@code holder.set(...)} 即可。
     *
     * @param meterName meter 名(常用 {@link #CACHE_HIT_RATIO} / {@link #SESSION_STAGE_DISTRIBUTION})
     * @param tags      tag 键值对,偶数长度(成对的 key/value)
     * @param holder    AtomicLong 数值持有者
     * @return 注册成功的 {@link AtomicLong} 引用,业务侧写它
     */
    public AtomicLong holderGauge(String meterName, String[] tags, AtomicLong holder) {
        Gauge.builder(meterName, holder, AtomicLong::doubleValue)
                .description("F-9 generic gauge (W2 callback)")
                .tags(tags)
                .register(meterRegistry);
        return holder;
    }

    /** 暴露 {@link MeterRegistry} 给业务侧直接 record Timer.Sample(测试场景更常用)。 */
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /**
     * gauge 计算的辅助函数(W2 用):(hit + miss) 计算命中率。
     *
     * <p>避免业务代码里写 lambda 的可读性损耗。
     */
    public static ToDoubleFunction<AtomicLong> ratio(AtomicLong hit, AtomicLong total) {
        return ignored -> {
            long t = total.get();
            if (t <= 0) return 0.0;
            return (double) hit.get() / (double) t;
        };
    }
}
