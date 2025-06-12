package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PriceCalendar {
    private Integer id;
    private Integer roomType;
    private BigDecimal price;
    private LocalDate date;
}
