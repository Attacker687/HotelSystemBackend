package com.winniethepooh.hotelsystembackend.exception;

public class UserPhoneInvalidException extends RuntimeException {
    public UserPhoneInvalidException(String message) {
        super(message);
    }
}
