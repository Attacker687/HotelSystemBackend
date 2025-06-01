package com.winniethepooh.hotelsystembackend.exception;

public class CategoryNameDuplicatedException extends RuntimeException {
    public CategoryNameDuplicatedException(String message) {
        super(message);
    }
}
