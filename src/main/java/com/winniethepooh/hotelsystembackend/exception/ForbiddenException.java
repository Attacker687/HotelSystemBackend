package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

/** 已登录但角色无权访问，HTTP 403。 */
public class ForbiddenException extends BusinessException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
