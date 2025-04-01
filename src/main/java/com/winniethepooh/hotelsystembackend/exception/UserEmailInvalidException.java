package com.winniethepooh.hotelsystembackend.exception;

public class UserEmailInvalidException extends RuntimeException {
    public UserEmailInvalidException(String message) {
        super(message);
    }
}
