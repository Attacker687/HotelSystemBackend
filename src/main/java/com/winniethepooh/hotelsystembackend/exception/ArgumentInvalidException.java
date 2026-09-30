package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class ArgumentInvalidException extends BusinessException {
    public ArgumentInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
