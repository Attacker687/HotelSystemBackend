package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DishVO {
    private Integer id;
    private Integer categoryId;
    private String categoryName;
    private String name;
    private String image;
    private BigDecimal price;
    private Integer status;
    private String description;
}
