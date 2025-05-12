package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QueryRoomsVO {
    private Integer id;
    private Integer number;
    private Integer type;
    private Integer status;
    private BigDecimal price;
}
