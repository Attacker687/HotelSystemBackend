package com.winniethepooh.hotelsystembackend.exception;

public class UserPasswordInvalidException extends RuntimeException {
    public UserPasswordInvalidException(String message) {
        super(message);
    }
}
