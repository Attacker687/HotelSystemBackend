package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DishTop10VO {
    private String name;
    private BigDecimal value;
}
