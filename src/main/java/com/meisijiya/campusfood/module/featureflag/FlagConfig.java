package com.meisijiya.campusfood.module.featureflag;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Feature Flag 单条配置(F-11 W1)— Redis hash value 的反序列化目标。
 *
 * <h2>字段语义</h2>
 * <ul>
 *   <li>{@link #mode} — 必填,4 种 {@link FlagMode} 之一;</li>
 *   <li>{@link #whitelist} — 仅 {@link FlagMode#WHITELIST_ONLY} 使用;其他模式允许 null / 空;</li>
 *   <li>{@link #percentage} — 仅 {@link FlagMode#PERCENTAGE} 使用,范围 [0, 100]。</li>
 * </ul>
 *
 * <h2>Redis hash value JSON 形式示例</h2>
 * <pre>
 *   recommend-v2        → {"mode":"PERCENTAGE","percentage":20}
 *   like-cache-bypass   → {"mode":"WHITELIST_ONLY","whitelist":[1,2,3]}
 *   merchant-detail-new → {"mode":"ALL_OFF"}
 * </pre>
 *
 * <h2>{@code @JsonIgnoreProperties} 防御</h2>
 * <p>Redis hash 字段未来可能扩展(如灰度发布期加 {@code rolloutEnd} 时间戳),未知字段必须
 * 容忍,不能让 Jackson 反序列化整个失败 → 整个 feature flag 体系挂掉。
 *
 * <h2>{@code equals / hashCode}</h2>
 * <p>用于 Caffeine cache key 比较 — 同一 {@code flagKey} 下若配置未变,
 * Service 端能直接用缓存 value 跳过 Redis hash 反序列化,提升性能。
 *
 * @author meisijiya
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlagConfig {

    private final FlagMode mode;
    private final Set<Long> whitelist;
    private final int percentage;

    /**
     * Jackson 反序列化入口(Redis hash value → {@link FlagConfig})。
     *
     * @param mode       必填,4 种枚举之一
     * @param whitelist  可选;{@code WHITELIST_ONLY} 时必填,其余可为 null
     * @param percentage 可选;{@code PERCENTAGE} 时必填且 ∈ [0, 100]
     */
    @JsonCreator
    public FlagConfig(
            @JsonProperty("mode") FlagMode mode,
            @JsonProperty("whitelist") Set<Long> whitelist,
            @JsonProperty("percentage") int percentage) {
        if (mode == null) {
            throw new IllegalArgumentException("FlagConfig.mode must be non-null");
        }
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException(
                    "FlagConfig.percentage must be in [0, 100], got " + percentage);
        }
        this.mode = mode;
        // 防御拷贝 + 不可变封装,避免外部修改后破坏 hashCode / equals 假设
        this.whitelist = whitelist == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(whitelist));
        this.percentage = percentage;
    }

    public FlagMode getMode() {
        return mode;
    }

    public Set<Long> getWhitelist() {
        return whitelist;
    }

    public int getPercentage() {
        return percentage;
    }

    /** {@link FlagMode#ALL_ON} 工厂方法。 */
    public static FlagConfig allOn() {
        return new FlagConfig(FlagMode.ALL_ON, null, 0);
    }

    /** {@link FlagMode#ALL_OFF} 工厂方法。 */
    public static FlagConfig allOff() {
        return new FlagConfig(FlagMode.ALL_OFF, null, 0);
    }

    /** {@link FlagMode#PERCENTAGE} 工厂方法(percentage ∈ [0, 100])。 */
    public static FlagConfig percentage(int percentage) {
        return new FlagConfig(FlagMode.PERCENTAGE, null, percentage);
    }

    /** {@link FlagMode#WHITELIST_ONLY} 工厂方法。 */
    public static FlagConfig whitelistOnly(Set<Long> whitelist) {
        return new FlagConfig(FlagMode.WHITELIST_ONLY, whitelist, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FlagConfig other)) {
            return false;
        }
        return percentage == other.percentage
                && mode == other.mode
                && Objects.equals(whitelist, other.whitelist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, whitelist, percentage);
    }

    @Override
    public String toString() {
        return "FlagConfig{mode=" + mode
                + ", whitelist=" + whitelist
                + ", percentage=" + percentage + '}';
    }
}