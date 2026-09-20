package com.meisijiya.campusfood.config;

import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer 业务指标注册中心(F-9 工程亮点)— 暴露静态 helper 让
 * LikeService / RecommendService / MerchantQueryService / SessionService 安全打点。
 *
 * <h2>4 个业务指标</h2>
 * <ul>
 *   <li>{@code like_count_total} (Counter) — LikeService.like 成功路径;tag: endpoint</li>
 *   <li>{@code recommend_latency_seconds} (Timer) — RecommendService.recommend;tags: endpoint + hit_tier(mocks/dashscope/fallback)</li>
 *   <li>{@code cache_hit_ratio} (Counter) — MerchantQueryService;tags: cache_name + hit_tier</li>
 *   <li>{@code session_stage_distribution} (Counter) — SessionService;tags: stage</li>
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
 *   <li>Counter / Timer 走 {@code builder().register(meterRegistry)} 是 lazy 注册:
 *       只有 first-write 时该 meter 才会出现在 registry,避免冷启动有 0 值的死指标。</li>
 *   <li>同一调用点多次注册同名同 tag 自动复用,不会重复打点。</li>
 *   <li>不带 Lombok,字段全包私有 + 显式构造器注入(F-1 起的项目惯例)。</li>
 * </ul>
 *
 * <h2>历史(F-9 W1 早期占位 Gauge 已废弃)</h2>
 * <p>F-9 W1 早期 {@code @PostConstruct registerPlaceholders()} 把
 * {@code cache_hit_ratio} / {@code session_stage_distribution} 预注册为 Gauge 占位
 * (tag {@code =_placeholder});F-9 W2 实际落地选了 Counter(timer-style 计数更准确)。
 * 两者同名不同类型 → Prometheus 启动时打 WARN
 * 「There is already an existing meter that is a GAUGE ... attempting to register a COUNTER」。
 * 2026-09-20 收尾:F-9 W1 的 Gauge 占位与 {@code holderGauge} helper 已删除,
 * Counter 走 lazy 注册,功能不变,WARN 消失。</p>
 *
 * @author meisijiya
 */
@Configuration
public class MicrometerConfig {

    /** {@code like_count_total} — tag {@code endpoint="like"}. */
    public static final String LIKE_COUNT = "like_count_total";

    /** {@code recommend_latency_seconds} — tags {@code endpoint} + {@code hit_tier}. */
    public static final String RECOMMEND_LATENCY = "recommend_latency_seconds";

    /** {@code cache_hit_ratio} — Counter, tags {@code cache_name} + {@code hit_tier}. */
    public static final String CACHE_HIT_RATIO = "cache_hit_ratio";

    /** {@code session_stage_distribution} — Counter, tag {@code stage}. */
    public static final String SESSION_STAGE_DISTRIBUTION = "session_stage_distribution";

    private final MeterRegistry meterRegistry;

    public MicrometerConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
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

    /** 暴露 {@link MeterRegistry} 给业务侧直接 record Timer.Sample(测试场景更常用)。 */
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }
}
