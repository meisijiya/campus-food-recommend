package com.meisijiya.campusfood.module.featureflag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature Flag 配置属性(F-11 W1)— {@code feature-flag.*} 前缀,
 * 由主类 {@code @ConfigurationPropertiesScan} 自动装配。
 *
 * <h2>{@code application.yml} 形态</h2>
 * <pre>
 *   feature-flag:
 *     enabled: true                 # 总开关:false 时任何 flag 都返 false(默认 true)
 *     default-flags:                # 启动期 Redis 无值时的 fallback;W3 在 IT 阶段种 Redis
 *       recommend-v2:
 *         mode: PERCENTAGE
 *         percentage: 20
 *       like-cache-bypass:
 *         mode: WHITELIST_ONLY
 *         whitelist: [1, 2, 3]
 *       merchant-detail-new:
 *         mode: ALL_OFF
 * </pre>
 *
 * <h2>设计取舍</h2>
 * <ul>
 *   <li>{@code enabled} = false 时 {@code FeatureFlagService.isEnabled} 直接返回 false,
 *       用于紧急下线整个 flag 体系(类似 kill switch)。</li>
 *   <li>{@code default-flags} 用 {@link LinkedHashMap} 保序,方便 admin 端列出时按
 *       yml 中顺序展示。</li>
 * </ul>
 *
 * @author meisijiya
 */
@ConfigurationProperties(prefix = "feature-flag")
public class FeatureFlagProperties {

    /** Feature Flag 总开关 — 关闭时所有 flag 都返 false。 */
    private boolean enabled = true;

    /** 默认 flag 配置(Redis 无值时的 fallback,Map<flagKey, FlagConfig>)。 */
    private Map<String, FlagConfig> defaultFlags = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, FlagConfig> getDefaultFlags() {
        return defaultFlags;
    }

    public void setDefaultFlags(Map<String, FlagConfig> defaultFlags) {
        this.defaultFlags = defaultFlags == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(defaultFlags);
    }

    /** 不可变视图(只读快照) — 给 Service 启动时一次性加载用。 */
    public Map<String, FlagConfig> defaultFlagsView() {
        return Collections.unmodifiableMap(defaultFlags);
    }
}