package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class UserEmailInvalidException extends BusinessException {
    public UserEmailInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
