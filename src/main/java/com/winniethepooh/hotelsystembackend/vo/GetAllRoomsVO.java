package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class GetAllRoomsVO {
    private Integer id;
    private Integer number;
    private String type;
    private String status;
    private BigDecimal price;
}
