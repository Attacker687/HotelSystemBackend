package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TimeCheckDTO {
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
}
