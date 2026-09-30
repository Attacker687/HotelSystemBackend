package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class UserIDCardInvalidException extends BusinessException {
    public UserIDCardInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
