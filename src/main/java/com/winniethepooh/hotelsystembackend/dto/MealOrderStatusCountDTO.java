package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

@Data
public class MealOrderStatusCountDTO {
    private Integer newOrderCount;
    private Integer pendingOrderCount;
    private Integer doneOrderCount;
}
