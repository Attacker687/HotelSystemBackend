package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class MismatchException extends BusinessException {
    public MismatchException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
