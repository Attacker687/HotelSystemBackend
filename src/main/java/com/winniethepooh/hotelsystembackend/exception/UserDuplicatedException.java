package com.winniethepooh.hotelsystembackend.exception;

public class UserDuplicatedException extends RuntimeException {
    public UserDuplicatedException(String message) {
        super(message);
    }
}
