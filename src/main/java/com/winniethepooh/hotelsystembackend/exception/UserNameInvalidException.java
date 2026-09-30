package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class UserNameInvalidException extends BusinessException {
    public UserNameInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
