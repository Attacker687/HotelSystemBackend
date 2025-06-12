package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class DynamicUpdatePriceDTO {
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer roomType;
    private BigDecimal price;
}
