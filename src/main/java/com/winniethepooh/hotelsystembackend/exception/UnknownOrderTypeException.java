package com.winniethepooh.hotelsystembackend.exception;

public class UnknownOrderTypeException extends RuntimeException {
    public UnknownOrderTypeException(String message) {
        super(message);
    }
}
