package com.meisijiya.campusfood.module.like;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meisijiya.campusfood.common.ApiResponse;
import com.meisijiya.campusfood.common.exception.ApiException;

/**
 * 点赞端点(F-5 B-5 bullet)— {@code POST /api/like/{merchantId}}。
 *
 * <h2>行为契约</h2>
 * <ul>
 *   <li>学生从 JWT 拿 {@code studentId} — 不接受客户端传 sid 防越权写他人点赞。</li>
 *   <li>首次点赞:返 {@code {code:0, message:"ok", data:{liked:true}}}</li>
 *   <li>60s 内重复点赞:返 {@code {code:0, message:"already liked", data:{liked:false}}}</li>
 *   <li>{@code merchantId} 为空或超长:抛 {@link ApiException}(由 {@code GlobalExceptionHandler}
 *       转 400 BAD_REQUEST)。</li>
 * </ul>
 *
 * <p>{@code @PreAuthorize("isAuthenticated()")} 与 {@code MerchantController} / {@code SessionController}
 * 同款双重护栏 — 全局 Security 链外加方法级注解,防止误配暴露。
 *
 * @author meisijiya
 */
@RestController
@RequestMapping("/api/like")
@PreAuthorize("isAuthenticated()")
public class LikeController {

    private final LikeService likeService;

    public LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    /**
     * 给指定商户点赞。
     *
     * @param user  当前登录用户 — {@link UserDetails#getUsername()} 即 studentId(JWT sub)
     * @param merchantId 路径变量,商户主键(非空 + 长度 ≤ 64)
     * @return ApiResponse 包裹的点赞结果;{@code data.liked=true} = 首次成功,
     *         {@code data.liked=false} = 60s 内重复
     * @throws ApiException 400:merchantId 空 / 超长 / 含非法字符
     */
    @PostMapping("/{merchantId}")
    public ApiResponse<LikeResult> like(@AuthenticationPrincipal UserDetails user,
                                         @PathVariable String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "merchantId must be non-blank");
        }
        if (merchantId.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "merchantId length must be <= 64");
        }
        String studentId = sidOf(user);
        boolean liked = likeService.like(studentId, merchantId);
        if (liked) {
            return ApiResponse.ok(new LikeResult(true));
        }
        // 重复点赞 — 走 ApiResponse.fail 但 code=0;语义同成功,前端按 message 区分
        return new ApiResponse<>(0, "already liked", new LikeResult(false));
    }

    private static String sidOf(UserDetails user) {
        if (user == null) {
            // @PreAuthorize 已经挡住匿名调用,这里是 defense-in-depth
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录(studentId 无法获取)");
        }
        return user.getUsername();
    }

    /**
     * 点赞结果 DTO:{@code {liked: true|false}}。
     *
     * @param liked {@code true} = 首次成功;{@code false} = 60s 内重复点赞
     */
    public record LikeResult(boolean liked) {
    }
}