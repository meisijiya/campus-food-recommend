package com.meisijiya.campusfood.common.exception;

/**
 * 错误码常量 — F-1 阶段使用最少几个,F-2+ 按需扩展。
 *
 * @author meisijiya
 */
public final class ErrorCode {

    private ErrorCode() {}

    /** 鉴权失败 / 未登录。 */
    public static final int UNAUTHORIZED = 40100;
    /** 无权限访问资源。 */
    public static final int FORBIDDEN = 40300;
    /** 资源不存在。 */
    public static final int NOT_FOUND = 40400;
    /** 参数校验失败。 */
    public static final int BAD_REQUEST = 40000;
    /** 业务错误(默认 catch-all)。 */
    public static final int BUSINESS_ERROR = 50000;
    /**
     * 限流拒绝(F-7)— 双层令牌桶任一层拒绝时返 HTTP 429,body code=42900。
     * <p>HTTP status 仍为 429(由 {@code RateLimitFilter} 直接写入 response),
     * 走 {@code ApiException} 路径会让 Spring 返回 500,因此 Filter 不抛异常,
     * 仅参考本常量生成 JSON 响应体。
     */
    public static final int RATE_LIMITED = 42900;
    /**
     * F-11:Feature Flag 不存在(flagKey 在 Redis hash / yml default-flags 中均无值)。
     * <p>由 {@code FeatureFlagController.check} / {@code FeatureFlagAdminController.get/set}
     * 在 flagKey 未注册时抛 {@code ApiException(NOT_FOUND, ...)} 触发。
     * <p>实际响应 code 仍由 {@code GlobalExceptionHandler.toCode(NOT_FOUND) -> 40400}
     * 统一映射;此处保留独立常量便于 log / 监控按业务维度 grep。
     */
    public static final int FEATURE_FLAG_NOT_FOUND = 40401;
    /**
     * F-11:Feature Flag 配置校验失败 — mode 缺失 / {@code PERCENTAGE} 模式下 percentage 越界 /
     * {@code WHITELIST_ONLY} 模式下 whitelist 缺失。
     * <p>由 {@code FeatureFlagAdminController.set} 在校验失败时抛 {@code ApiException(BAD_REQUEST, ...)},
     * 响应 code 由 {@code GlobalExceptionHandler.toCode(BAD_REQUEST) -> 40000} 映射。
     */
    public static final int FEATURE_FLAG_INVALID_CONFIG = 40001;
}