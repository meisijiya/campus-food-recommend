package com.meisijiya.campusfood.controller.featureflag;

import com.meisijiya.campusfood.module.featureflag.FlagMode;

/**
 * F-11 W2 — Feature Flag check 响应 DTO。
 *
 * <p>{@code /api/feature-flag/{flagKey}/check} 端点直接序列化本类为 JSON。
 *
 * @param flagKey   flag 名
 * @param enabled   当前 studentId 的最终决策
 * @param mode      flag 配置的 {@link FlagMode}
 * @param studentId 查询参数 studentId(可为 null)
 */
public record FeatureFlagCheckResponse(
        String flagKey,
        boolean enabled,
        FlagMode mode,
        Long studentId) {

    public static FeatureFlagCheckResponse of(String flagKey, boolean enabled,
                                              FlagMode mode, Long studentId) {
        return new FeatureFlagCheckResponse(flagKey, enabled, mode, studentId);
    }
}