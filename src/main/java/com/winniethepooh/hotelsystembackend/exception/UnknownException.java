package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class UnknownException extends BusinessException {
    public UnknownException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
