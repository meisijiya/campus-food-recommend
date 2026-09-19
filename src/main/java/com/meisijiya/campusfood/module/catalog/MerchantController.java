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
     * @param id 路径变量,商户主键
     * @return ApiResponse 包装的 Merchant;商户不存在抛 404(ApiException → GlobalExceptionHandler → 40400 NOT_FOUND)
     */
    @GetMapping("/{id}")
    public ApiResponse<Merchant> getById(@PathVariable String id) {
        Merchant merchant = service.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "merchant not found: " + id));
        return ApiResponse.ok(merchant);
    }

    /**
     * 按 zoneId 列出商户。路径:{@code GET /api/merchant?zoneId=Z-1}。
     *
     * <p>空 zoneId 由前端保证,这里不做参数校验(简化 demo;真实场景应加 {@code @NotBlank});
     * zoneId 不存在时返空列表而非 404,符合"按条件查询"的语义。
     *
     * @param zoneId query 参数,校区 / 区域 ID
     * @return ApiResponse 包装的商户列表(可能为空)
     */
    @GetMapping(params = "zoneId")
    public ApiResponse<List<Merchant>> getByZoneId(@RequestParam String zoneId) {
        return ApiResponse.ok(service.findByZoneId(zoneId));
    }
}