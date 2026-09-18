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

    public HttpStatus status() {
        return status;
    }
}