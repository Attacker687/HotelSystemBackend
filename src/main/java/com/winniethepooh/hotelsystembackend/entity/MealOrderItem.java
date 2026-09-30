package com.winniethepooh.hotelsystembackend.entity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MealOrderItem {
    private Long id;
    private Integer mealOrderId;
    private Long dishId; // 前端给
    @NotNull(message = "菜品数量不能为空")
    @Min(value = 1, message = "菜品数量必须大于0")
    private Integer quantity; // 前端给
    private BigDecimal unitPrice; // 前端给
    private BigDecimal totalPrice;
    private String name;
}
