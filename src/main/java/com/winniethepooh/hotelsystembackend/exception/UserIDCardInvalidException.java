package com.winniethepooh.hotelsystembackend.exception;

public class UserIDCardInvalidException extends RuntimeException {
    public UserIDCardInvalidException(String message) {
        super(message);
    }
}
