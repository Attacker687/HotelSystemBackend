package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class RoomNumberDuplicatedException extends BusinessException {
    public RoomNumberDuplicatedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
