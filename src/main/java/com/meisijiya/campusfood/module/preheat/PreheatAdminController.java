package com.meisijiya.campusfood.module.preheat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meisijiya.campusfood.common.ApiResponse;

/**
 * 凌晨预热管理接口(F-4)— 仅在 dev profile 启用,用于手动触发预热而不必等到凌晨 3 点。
 *
 * <h2>使用方式</h2>
 * <pre>
 *   curl -X POST http://localhost:8080/admin/preheat/trigger
 * </pre>
 *
 * <h2>鉴权</h2>
 * <p>{@link com.meisijiya.campusfood.config.SecurityConfig} 将 {@code /admin/preheat/**} 加到 permitAll 列表,
 * dev profile 下无需 JWT 即可触发(便于 demo 与新人 onboarding)。
 *
 * <p>prod profile 下本 Controller 不存在({@code @Profile("dev")} 排除 bean);若未来 prod 也需触发,
 * 移除 {@code @Profile} + 在 SecurityConfig 加 ADMIN 角色鉴权即可,无须改本类业务逻辑。
 *
 * @author meisijiya
 */
@RestController
@RequestMapping("/admin/preheat")
@Profile("dev")
class PreheatAdminController {

    private static final Logger log = LoggerFactory.getLogger(PreheatAdminController.class);

    private final HeatJobPreheater preheater;

    PreheatAdminController(HeatJobPreheater preheater) {
        this.preheater = preheater;
    }

    /**
     * 手动触发预热,返回摘要 JSON。
     *
     * @return ApiResponse 包装的预热摘要(timestamp + merchantCount + zoneCount + hotCount + elapsedMs)
     */
    @PostMapping("/trigger")
    public ApiResponse<HeatJobPreheater.PreheatSummary> trigger() {
        log.info("PreheatAdminController trigger preheat");
        HeatJobPreheater.PreheatSummary summary = preheater.preheat();
        return ApiResponse.ok(summary);
    }
}