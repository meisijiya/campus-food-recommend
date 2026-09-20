package com.meisijiya.campusfood.controller.featureflag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.meisijiya.campusfood.common.ApiResponse;
import com.meisijiya.campusfood.common.exception.ApiException;
import com.meisijiya.campusfood.common.exception.ErrorCode;
import com.meisijiya.campusfood.module.featureflag.FeatureFlagService;
import com.meisijiya.campusfood.module.featureflag.FlagConfig;

/**
 * F-11 公开 Feature Flag check 接口(F-11 W2)— 招实习 demo 用,
 * 任何人无需登录即可查询某 flag 对某 studentId 的最终决策。
 *
 * <h2>端点</h2>
 * <pre>
 *   GET /api/feature-flag/{flagKey}/check?studentId={可选}
 *   200 → ApiResponse{code:0, data:{flagKey, enabled, mode, studentId}}
 *   404 → flagKey 不存在(FEATURE_FLAG_NOT_FOUND)
 * </pre>
 *
 * <h2>为何要单独调 {@code getConfig} 判存在</h2>
 * <p>{@link FeatureFlagService#isEnabled} 对未知 flagKey 安全默认返回 false(防御性设计),
 * 但 demo 场景下客户端需要明确知道"这个 flag 没注册"以便排查配置错误。
 * 因此 controller 先调 {@link FeatureFlagService#getConfig},null 时抛 404,
 * 否则再调 {@code isEnabled} 拿决策。
 *
 * <h2>鉴权</h2>
 * <p>由 {@link com.meisijiya.campusfood.config.SecurityConfig}
 * 把 {@code /api/feature-flag/**} 加入 {@code permitAll};无需方法级注解。
 *
 * @author meisijiya
 */
@RestController
@RequestMapping("/api/feature-flag")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    public FeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    /**
     * 查询某 flag 对某 studentId 的最终决策。
     *
     * @param flagKey   路径变量;非空
     * @param studentId 查询参数;可选,缺失时为 null(灰度决策走安全路径)
     * @return ApiResponse 包装的 {@link FeatureFlagCheckResponse}
     * @throws ApiException 404 — flagKey 未在 Redis hash / yml default-flags 注册
     */
    @GetMapping("/{flagKey}/check")
    public ApiResponse<FeatureFlagCheckResponse> check(
            @PathVariable String flagKey,
            @RequestParam(name = "studentId", required = false) Long studentId) {
        if (flagKey == null || flagKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "flagKey must be non-blank");
        }

        FlagConfig config = featureFlagService.getConfig(flagKey);
        if (config == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "feature flag not found: " + flagKey);
        }

        boolean enabled = featureFlagService.isEnabled(flagKey, studentId);
        return ApiResponse.ok(
                FeatureFlagCheckResponse.of(flagKey, enabled, config.getMode(), studentId));
    }
}