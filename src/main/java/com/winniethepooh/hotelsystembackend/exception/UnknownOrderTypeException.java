package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class UnknownOrderTypeException extends BusinessException {
    public UnknownOrderTypeException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
