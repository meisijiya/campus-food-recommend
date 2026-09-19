package com.meisijiya.campusfood.module.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.meisijiya.campusfood.common.ApiResponse;
import com.meisijiya.campusfood.common.exception.ApiException;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;

/**
 * 商户读模型接口(F-4)— 暴露单商户 / 区域商户两支 GET,供前端详情页与列表页使用。
 *
 * <h2>F-4 中间态</h2>
 * 数据走 L2 MySQL,经 {@link MerchantQueryService} 暴露;F-5 升级 L0/L1/L2 时
 * 本 Controller 与 URL 契约不变,只换 Service 实现。
 *
 * <h2>鉴权</h2>
 * F-1 {@code SecurityConfig} 已通过 {@code anyRequest().authenticated()} 全局覆盖,
 * 这里再加 {@code @PreAuthorize("isAuthenticated()")} 是 ticket R1 review 阶段统一加的
 * 双重护栏(防止误把 Controller 暴露成 permitAll 而全局 Security 链被改动时无人察觉)。
 *
 * @author meisijiya
 */
@RestController
@RequestMapping("/api/merchant")
@PreAuthorize("isAuthenticated()")
class MerchantController {

    private final MerchantQueryService service;

    MerchantController(MerchantQueryService service) {
        this.service = service;
    }

    /**
     * 单商户详情。路径:{@code GET /api/merchant/{id}}。
     *
     * @param id 路径变量,商户主键(F-4 review fix:加 length 上限防 DoS + 空值短路)
     * @return ApiResponse 包装的 Merchant;商户不存在抛 404(ApiException → GlobalExceptionHandler → 40400 NOT_FOUND)
     */
    @GetMapping("/{id}")
    public ApiResponse<Merchant> getById(@PathVariable String id) {
        if (id == null || id.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "merchant id must be non-blank");
        }
        if (id.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "merchant id length must be <= 64");
        }
        Merchant merchant = service.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "merchant not found: " + id));
        return ApiResponse.ok(merchant);
    }

    /**
     * 按 zoneId 列出商户。路径:{@code GET /api/merchant?zoneId=Z-1}。
     *
     * <p>F-4 review fix:加 zoneId blank 校验(与 {@code RedisShardedWriter.validateKeySegment}
     * 契约一致,避免"读路径放行 + 写路径抛 IllegalArgumentException"的双标);
     * 加 length 上限防 DoS。zoneId 不存在时返空列表而非 404,符合"按条件查询"的语义。
     *
     * @param zoneId query 参数,校区 / 区域 ID(非空,长度 ≤ 64)
     * @return ApiResponse 包装的商户列表(可能为空)
     */
    @GetMapping(params = "zoneId")
    public ApiResponse<List<Merchant>> getByZoneId(@RequestParam String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ApiResponse.ok(List.of());
        }
        if (zoneId.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "zoneId length must be <= 64");
        }
        return ApiResponse.ok(service.findByZoneId(zoneId));
    }
}