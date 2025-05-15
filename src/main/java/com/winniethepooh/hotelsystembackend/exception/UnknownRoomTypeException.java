package com.winniethepooh.hotelsystembackend.exception;

public class UnknownRoomTypeException extends RuntimeException {
    public UnknownRoomTypeException(String message) {
        super(message);
    }
}
