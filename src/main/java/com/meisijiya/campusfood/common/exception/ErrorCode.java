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
}