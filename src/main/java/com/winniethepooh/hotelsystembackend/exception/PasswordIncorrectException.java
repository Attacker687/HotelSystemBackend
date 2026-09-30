package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class PasswordIncorrectException extends BusinessException {
    public PasswordIncorrectException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
