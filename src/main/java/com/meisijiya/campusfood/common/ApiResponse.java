package com.meisijiya.campusfood.common;

/**
 * 统一 API 响应:F-1 起所有接口返 {@code {code, message, data}}。
 *
 * @param code    0 成功;非 0 见 {@link com.meisijiya.campusfood.common.exception.ErrorCode}
 * @param message 人类可读消息
 * @param data    业务数据(可为 null)
 * @author meisijiya
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}