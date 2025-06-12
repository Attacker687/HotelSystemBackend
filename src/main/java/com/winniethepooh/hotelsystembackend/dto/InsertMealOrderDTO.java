package com.winniethepooh.hotelsystembackend.dto;

import com.winniethepooh.hotelsystembackend.entity.MealOrderItem;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class InsertMealOrderDTO {
    private Integer id;
    private Integer userId;
    private String address; // 前端给
    private List<MealOrderItem> itemList; // 前端给
    private String remarks;  // 前端给
    private BigDecimal totalAmount; // 前端给
}
