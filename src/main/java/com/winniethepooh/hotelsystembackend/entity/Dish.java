package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class Dish {
    private Integer id;
    private String name;
    private Integer categoryId;
    private BigDecimal price;
    private String image;
    private String description;
    private Integer status;
}
