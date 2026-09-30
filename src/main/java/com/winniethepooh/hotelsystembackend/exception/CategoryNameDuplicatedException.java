package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class CategoryNameDuplicatedException extends BusinessException {
    public CategoryNameDuplicatedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
