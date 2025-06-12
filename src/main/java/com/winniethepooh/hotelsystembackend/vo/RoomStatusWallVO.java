package com.winniethepooh.hotelsystembackend.vo;

import com.winniethepooh.hotelsystembackend.entity.Individual;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RoomStatusWallVO {
    private Integer id;
    private Integer roomNumber;
    private Integer roomType;
    private Integer status;
    private Individual individual;
    private BigDecimal price;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
}
