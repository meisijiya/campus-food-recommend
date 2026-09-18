package com.meisijiya.campusfood.module.catalog.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meisijiya.campusfood.common.ApiResponse;

/**
 * 会话槽位 4 端点(F-2;对应 ticket acceptance #7-#10)。
 *
 * <p>{@code sid} 始终取自 JWT sub(即 {@code studentId} 的字符串形式,见 plan.md §Global Constraints #6) —
 * 客户端不传 sid,避免越权写别人会话。
 *
 * @author meisijiya
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 初始化或重置会话(stage=INIT,清空所有槽位)。 */
    @PostMapping("/init")
    public ApiResponse<SessionContext> init(@AuthenticationPrincipal UserDetails user) {
        String sid = sidOf(user);
        return ApiResponse.ok(sessionService.init(sid));
    }

    /** 写入 zoneId(stage→ZONE;非法跳转由 GlobalExceptionHandler 转 400)。 */
    @PostMapping("/zone")
    public ApiResponse<SessionContext> zone(@AuthenticationPrincipal UserDetails user,
                                            @Valid @RequestBody SlotRequest body) {
        String sid = sidOf(user);
        return ApiResponse.ok(sessionService.setZone(sid, body.value()));
    }

    /** 写入 cuisineId(stage→CUISINE;非法跳转 → 400)。 */
    @PostMapping("/cuisine")
    public ApiResponse<SessionContext> cuisine(@AuthenticationPrincipal UserDetails user,
                                               @Valid @RequestBody SlotRequest body) {
        String sid = sidOf(user);
        return ApiResponse.ok(sessionService.setCuisine(sid, body.value()));
    }

    /** 写入 merchantId(stage→MERCHANT;非法跳转 → 400)。 */
    @PostMapping("/merchant")
    public ApiResponse<SessionContext> merchant(@AuthenticationPrincipal UserDetails user,
                                                @Valid @RequestBody SlotRequest body) {
        String sid = sidOf(user);
        return ApiResponse.ok(sessionService.setMerchant(sid, body.value()));
    }

    private static String sidOf(UserDetails user) {
        if (user == null) {
            throw new IllegalStateException("未登录(sid 无法获取)");
        }
        // plan.md §Global Constraints #6:session.<sid> == JWT sub == studentId 字符串形式
        return user.getUsername();
    }

    /** 槽位写入请求 DTO(zoneId / cuisineId / merchantId 通用)。 */
    public record SlotRequest(@NotBlank String value) {}
}