package com.winniethepooh.hotelsystembackend.exception;

public class RoomNumberDuplicatedException extends RuntimeException {
    public RoomNumberDuplicatedException(String message) {
        super(message);
    }
}
