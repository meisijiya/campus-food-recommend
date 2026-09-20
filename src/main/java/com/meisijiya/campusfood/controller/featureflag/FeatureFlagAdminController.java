package com.meisijiya.campusfood.controller.featureflag;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.meisijiya.campusfood.common.ApiResponse;
import com.meisijiya.campusfood.common.exception.ApiException;
import com.meisijiya.campusfood.module.featureflag.FeatureFlagService;
import com.meisijiya.campusfood.module.featureflag.FlagConfig;
import com.meisijiya.campusfood.module.featureflag.FlagMode;

/**
 * F-11 Admin Feature Flag 接口(F-11 W2)— 改 Redis hash 配置中心;
 * 生产里走 ADMIN 角色鉴权。
 *
 * <h2>端点</h2>
 * <pre>
 *   GET    /admin/feature-flag                  → 列出所有 flagKey(去重 + 排序)
 *   GET    /admin/feature-flag/{flagKey}        → 读单个 flag 配置
 *   POST   /admin/feature-flag/{flagKey}        → 修改 flag 配置(body: FlagConfig)
 * </pre>
 *
 * <h2>POST 校验</h2>
 * <ul>
 *   <li>{@code mode} 必填 — {@link FlagConfig} 构造器已 throw,这里冗余防御;</li>
 *   <li>{@link FlagMode#PERCENTAGE} 模式:percentage 必须 ∈ [0, 100](已由 {@link FlagConfig}
 *       构造器 throw,但 controller 多加一次以在错误信息里精确指出 mode);</li>
 *   <li>{@link FlagMode#WHITELIST_ONLY} 模式:whitelist 不可为 null(空集允许)。</li>
 * </ul>
 *
 * <h2>鉴权</h2>
 * <p>双重护栏:
 * <ol>
 *   <li>{@link com.meisijiya.campusfood.config.SecurityConfig}
 *       在 authorizeHttpRequests 把 {@code /admin/feature-flag/**} 设为 {@code hasRole("ADMIN")};</li>
 *   <li>本类加类级 {@code @PreAuthorize("hasRole('ADMIN')")} 作 method-level defense-in-depth,
 *       防未来 SecurityConfig 误配把 admin 路径意外放出。</li>
 * </ol>
 *
 * @author meisijiya
 */
@RestController
@RequestMapping("/admin/feature-flag")
@PreAuthorize("hasRole('ADMIN')")
public class FeatureFlagAdminController {

    private final FeatureFlagService featureFlagService;
    private final ObjectMapper objectMapper;

    public FeatureFlagAdminController(FeatureFlagService featureFlagService,
                                      ObjectMapper objectMapper) {
        this.featureFlagService = featureFlagService;
        this.objectMapper = objectMapper;
    }

    /**
     * 列出当前所有 flagKey(Redis hash 已加载 ∪ yml default-flags,TreeSet 去重排序)。
     */
    @GetMapping
    public ApiResponse<Set<String>> list() {
        return ApiResponse.ok(featureFlagService.listFlags());
    }

    /**
     * 读单个 flag 配置。
     *
     * @throws ApiException 404 — flagKey 未注册
     */
    @GetMapping("/{flagKey}")
    public ApiResponse<FlagConfig> get(@PathVariable String flagKey) {
        if (flagKey == null || flagKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "flagKey must be non-blank");
        }
        FlagConfig config = featureFlagService.getConfig(flagKey);
        if (config == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "feature flag not found: " + flagKey);
        }
        return ApiResponse.ok(config);
    }

    /**
     * 修改 flag 配置 — 同步写 Redis hash + 失效 Caffeine(由
     * {@link FeatureFlagService#setConfig(String, FlagConfig)} 内部完成)。
     *
     * <p>校验失败一律返 {@code BAD_REQUEST}(HTTP 400),{@code GlobalExceptionHandler}
     * 会把 body code 映射到 {@link com.meisijiya.campusfood.common.exception.ErrorCode#BAD_REQUEST}.
     *
     * <h2>为何 body 是 {@link JsonNode} 不是 {@link FlagConfig}</h2>
     * <p>直接接 {@code FlagConfig} 会让 Jackson 反序列化时构造器对 {@code percentage < 0} 等场景
     * 抛 {@link IllegalArgumentException},被 Spring 包成 {@code HttpMessageNotReadableException}
     * 走 {@code GlobalExceptionHandler.handleAny} 兜底成 500 — 与"参数校验失败应返 400"的
     * 业务契约不符。改接 {@link JsonNode} 让 Jackson 永不拒绝,再由本 controller 在 ObjectMapper
     * 转 {@code FlagConfig} 阶段 catch {@code IAE} 转 {@code ApiException(BAD_REQUEST)}。
     *
     * @throws ApiException 400 — 配置校验失败({@code FEATURE_FLAG_INVALID_CONFIG} 语义)
     */
    @PostMapping("/{flagKey}")
    public ApiResponse<FlagConfig> set(@PathVariable String flagKey,
                                       @RequestBody JsonNode body) {
        if (flagKey == null || flagKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "flagKey must be non-blank");
        }
        if (body == null || body.isNull() || body.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "config body must be non-empty JSON object");
        }

        // 1. JsonNode → FlagConfig(FlagConfig 构造器对非法 percentage / null mode 会 throw IAE)
        FlagConfig config;
        try {
            config = objectMapper.treeToValue(body, FlagConfig.class);
        } catch (IllegalArgumentException e) {
            // FlagConfig 构造器校验失败 — 转成我们的 BAD_REQUEST 业务异常,避免被 GEH 兜底成 500
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "flag config invalid: " + e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // body JSON 结构不符合 FlagConfig(字段类型错误等)— 也归类为 BAD_REQUEST
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "flag config invalid: malformed JSON (" + e.getOriginalMessage() + ")");
        }

        if (config == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "flag config invalid: deserialized to null");
        }

        // 2. 按 mode 二次校验(校验失败时 controller 给精确报错;FlagConfig 已构造但模式不一定语义合法)
        FlagMode mode = config.getMode();
        if (mode == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "flag config invalid: mode must be non-null");
        }
        switch (mode) {
            case PERCENTAGE -> {
                int percentage = config.getPercentage();
                if (percentage < 0 || percentage > 100) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "flag config invalid: PERCENTAGE mode requires percentage in [0, 100], got "
                                    + percentage);
                }
            }
            case WHITELIST_ONLY -> {
                if (config.getWhitelist() == null) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "flag config invalid: WHITELIST_ONLY mode requires whitelist (non-null, may be empty)");
                }
            }
            case ALL_ON, ALL_OFF -> {
                /* no-op — 这两种模式不依赖 percentage / whitelist */
            }
        }

        featureFlagService.setConfig(flagKey, config);
        return ApiResponse.ok(config);
    }
}