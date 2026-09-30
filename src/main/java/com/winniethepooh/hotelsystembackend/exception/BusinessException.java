package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：message 是给用户看的具体原因，status 是响应的 HTTP 状态。
 * GlobalExceptionHandler 统一按 status 返回 Result.error(message)。
 * 取值约定：参数错误 400，未登录 401，无权限 403，不存在 404，重复或状态冲突 409。
 */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
