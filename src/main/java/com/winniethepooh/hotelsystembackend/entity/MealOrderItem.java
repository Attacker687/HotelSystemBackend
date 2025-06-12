package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MealOrderItem {
    private Long id;
    private Integer mealOrderId;
    private Long dishId; // 前端给
    private Integer quantity; // 前端给
    private BigDecimal unitPrice; // 前端给
    private BigDecimal totalPrice;
    private String name;
}
