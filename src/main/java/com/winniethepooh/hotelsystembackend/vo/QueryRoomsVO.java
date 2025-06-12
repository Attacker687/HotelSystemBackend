package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.math.BigDecimal;


@Data
public class QueryRoomsVO {
    private Integer id;
    private Integer roomNumber;
    private Integer roomType;
    private Integer status;
    private String image;
    private Integer floor;
    private Integer capacity;
    private String description;
    private BigDecimal price;
}
