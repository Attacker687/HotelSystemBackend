package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class UserPasswordInvalidException extends BusinessException {
    public UserPasswordInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
