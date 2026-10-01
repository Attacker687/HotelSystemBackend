package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;

@Data
public class RoomQuoteVO {
    private String roomNumber;
    private Integer roomType;
    private Integer floor;
    private LocalDateTime checkIn;
    private LocalDateTime checkOut;
    private LinkedHashMap<LocalDate, BigDecimal> nights;
    private BigDecimal total;
}
