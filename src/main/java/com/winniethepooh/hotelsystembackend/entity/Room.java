package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Room {
    private Long id;                 // 房间id
    private String roomNumber;      // 房间号
    private Integer roomType;     // 房型
    private Integer floor;          // 楼层号
    private Integer status;         // 房间状态（0可用，1占用，2清洁中）
    private BigDecimal price;       // 价格
    private String image;
    private LocalDateTime createdAt;  // 创建时间
    private LocalDateTime updatedAt;  // 最近一次更改时间
}
