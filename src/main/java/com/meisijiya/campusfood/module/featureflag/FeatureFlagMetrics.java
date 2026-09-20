package com.meisijiya.campusfood.module.featureflag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Feature Flag 指标封装(F-11 W1)— 每次 {@code FeatureFlagService.isEnabled}
 * 调用后自增 {@link #FEATURE_FLAG_CHECK_TOTAL} Counter,tag 维度 {@code flag}
 * + {@code decision},供 Prometheus / Grafana 反向验证灰度比例(PERCENTAGE 模式
 * 应当看到 true:false ≈ percentage:(100-percentage))。
 *
 * <h2>设计纪律</h2>
 * <ul>
 *   <li>遵循 F-9 W1 立的 Micrometer 约定:同一 name + tags 在 registry 内是 lazy
 *       复用,不会重复打点(详见 {@code MicrometerConfig})。</li>
 *   <li>{@code decision} tag 用 {@link String#valueOf(boolean)} → {@code "true"/"false"},
 *       避免 boolean 在 Prometheus 序列化为 1/0 与其他 metric 不一致。</li>
 *   <li>本类只暴露 {@link #incrementCheck(String, boolean)},不允许业务侧直接拿
 *       Counter 引用,保持封装边界。</li>
 * </ul>
 *
 * @author meisijiya
 */
public class FeatureFlagMetrics {

    /** Counter 名 — Prometheus 序列化为 {@code feature_flag_check_total}。 */
    public static final String FEATURE_FLAG_CHECK_TOTAL = "feature_flag_check_total";

    private final MeterRegistry meterRegistry;

    public FeatureFlagMetrics(MeterRegistry meterRegistry) {
        if (meterRegistry == null) {
            throw new IllegalArgumentException("meterRegistry must be non-null");
        }
        this.meterRegistry = meterRegistry;
    }

    /**
     * 自增一次 check Counter(每次 {@code FeatureFlagService.isEnabled} 调用都会走)。
     *
     * @param flagKey  flag 名(也是 Micrometer tag {@code flag})
     * @param decision 最终决策结果(true = 开启 / false = 关闭)
     */
    public void incrementCheck(String flagKey, boolean decision) {
        if (flagKey == null || flagKey.isBlank()) {
            // 防御:不让脏 key 污染 Prometheus tag 集合
            return;
        }
        Counter.builder(FEATURE_FLAG_CHECK_TOTAL)
                .description("Feature flag check counter — tags: flag, decision")
                .tag("flag", flagKey)
                .tag("decision", String.valueOf(decision))
                .register(meterRegistry)
                .increment();
    }
}