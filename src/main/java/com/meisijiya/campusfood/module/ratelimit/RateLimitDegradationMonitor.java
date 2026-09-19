package com.meisijiya.campusfood.module.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * F-7 W2:限流降级计数器 — Redis → Caffeine 切换时自增。
 *
 * <p>指标名 {@code rate_limiter_degraded_total}(spec §F-7 acceptance):
 * <ul>
 *   <li>{@code source=redis_to_caffeine} — Redis 主路径抛 {@link RateLimiterBackendException}
 *       时自增</li>
 *   <li>{@code source=caffeine_to_redis} — Redis 恢复后(W1 端探测)回切时自增,
 *       由 W1 在自己的代码里调用本组件方法(F-7 W3 IT 验证该路径)</li>
 * </ul>
 *
 * <h2>与 F-9 的关系</h2>
 * <p>本指标名与 F-9 的业务指标并列,但本指标属于"系统降级"
 * 而非业务成功。不走 F-9 的 helper — 本组件直接持有自己的
 * {@link MeterRegistry} 引用,自注册 Counter。
 *
 * <h2>不抛异常</h2>
 * <p>{@link #recordDegradation(String)} 是 fire-and-forget — Micrometer
 * Counter 自增无失败路径,但仍用 try/catch 兜底,防止 Micrometer 内部 bug
 * 把限流主链路搞挂。
 *
 * @author meisijiya
 */
@Component
public class RateLimitDegradationMonitor {

    /** Micrometer metric name — 业务方按此名在 Grafana / Prometheus 配 dashboard。 */
    public static final String DEGRADED_TOTAL = "rate_limiter_degraded_total";

    public static final String SOURCE_REDIS_TO_CAFFEINE = "redis_to_caffeine";
    public static final String SOURCE_CAFFEINE_TO_REDIS = "caffeine_to_redis";

    private final MeterRegistry meterRegistry;

    public RateLimitDegradationMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 自增降级计数器。
     *
     * @param source 降级方向标签,见 {@link #SOURCE_REDIS_TO_CAFFEINE} /
     *               {@link #SOURCE_CAFFEINE_TO_REDIS}
     */
    public void recordDegradation(String source) {
        try {
            Counter.builder(DEGRADED_TOTAL)
                    .description("Rate limiter degradation events (Redis↔Caffeine fallback)")
                    .tag("source", source)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            // Micrometer 内部 bug 不应该把限流主链路搞挂 — log and swallow
            System.err.println("[RateLimitDegradationMonitor] counter increment failed: " + e.getMessage());
        }
    }
}
