package com.meisijiya.campusfood.module.recommend;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meisijiya.campusfood.common.ApiResponse;

/**
 * 推荐端点(F-2 占位 + Skill 注入;对应 ticket acceptance #11)。
 *
 * <p>{@code POST /api/recommend} — 拿 JWT sub 作为 sid 读会话上下文,按当前 stage
 * 注入对应 Skill,调 {@link RecommendService#recommend(String)}。
 *
 * @author meisijiya
 */
@RestController
@RequestMapping("/api/recommend")
public class RecommendController {

    private final RecommendService recommendService;

    public RecommendController(RecommendService recommendService) {
        this.recommendService = recommendService;
    }

    @PostMapping
    public ApiResponse<RecommendService.RecommendationResult> recommend(
            @AuthenticationPrincipal UserDetails user) {
        String sid = user.getUsername();
        return ApiResponse.ok(recommendService.recommend(sid));
    }
}