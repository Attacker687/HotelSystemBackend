package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InsertRoomOrderDTO {
    private String name;
    private String phone;
    private String idCard;
    private String roomNumber;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
}
