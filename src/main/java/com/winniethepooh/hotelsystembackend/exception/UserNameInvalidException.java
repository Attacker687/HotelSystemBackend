package com.winniethepooh.hotelsystembackend.exception;

public class UserNameInvalidException extends RuntimeException {
    public UserNameInvalidException(String message) {
        super(message);
    }
}
