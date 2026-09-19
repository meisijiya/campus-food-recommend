package com.meisijiya.campusfood.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类 — Controller / Service 抛出后由 {@code GlobalExceptionHandler} 转 {@code ApiResponse}。
 *
 * @author meisijiya
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * F-7 helper:429 限流异常工厂。{@code RateLimitFilter} 本身不抛(避免被
     * {@code GlobalExceptionHandler} 吃成 500),仅在 Controller 层需要"业务维度"
     * 拒绝时使用此工厂抛 {@code ApiException}。Filter 侧直接写 response。
     */
    public static ApiException rateLimited(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public HttpStatus status() {
        return status;
    }
}