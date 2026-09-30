package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class UserPhoneInvalidException extends BusinessException {
    public UserPhoneInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
