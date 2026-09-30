package com.winniethepooh.hotelsystembackend.exception;

import org.springframework.http.HttpStatus;

public class UnknownRoomTypeException extends BusinessException {
    public UnknownRoomTypeException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
