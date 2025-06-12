package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateDishDTO {
    private String name;
    private Integer categoryId;
    private BigDecimal price;
    private String image;
    private String description;
    private Integer status;
}
