package com.meisijiya.campusfood.module.catalog.session;

import org.springframework.http.HttpStatus;

import com.meisijiya.campusfood.common.exception.ApiException;

/**
 * 非法槽位跳转 — F-2 状态机严格单向(INIT → ZONE → CUISINE → MERCHANT),
 * 越级或回退都视为非法跳转。
 *
 * <p>由 {@code GlobalExceptionHandler} 统一转 HTTP 400(见 {@link ErrorCode#BAD_REQUEST})。
 *
 * @author meisijiya
 */
public class IllegalSlotTransitionException extends ApiException {

    public IllegalSlotTransitionException(SessionStage from, SessionStage to) {
        super(HttpStatus.BAD_REQUEST,
                "非法会话槽位跳转:" + (from == null ? "null" : from.name())
                        + " → " + (to == null ? "null" : to.name())
                        + " (状态机严格单向,只能 INIT→ZONE→CUISINE→MERCHANT)");
    }
}