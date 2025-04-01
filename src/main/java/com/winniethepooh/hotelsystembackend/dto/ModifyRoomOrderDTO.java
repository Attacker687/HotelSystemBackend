package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ModifyRoomOrderDTO {
    private String roomId;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private Integer status;
}
