package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class DuplicatedException extends BusinessException {
    public DuplicatedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
