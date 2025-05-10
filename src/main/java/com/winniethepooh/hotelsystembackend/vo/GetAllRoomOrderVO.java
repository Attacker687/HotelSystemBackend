package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GetAllRoomOrderVO {
    private Integer id;
    private String name;
    private String phone;
    private String idCard;
    private String roomNumber;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private String status;
}
