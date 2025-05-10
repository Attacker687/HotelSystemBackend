package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RoomType {
    private Integer id;               // 房型唯一标识符
    private String name;             // 房型名称
    private BigDecimal price;        // 房型价格
    private String bedType;          // 床型（如 "单人床"、"双人床"）
    private Integer capacity;        // 容量（最多可住人数）
    private Integer area;            // 面积（平方米）
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间
}
