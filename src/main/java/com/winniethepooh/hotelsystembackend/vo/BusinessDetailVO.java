package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BusinessDetailVO {
    private LocalDate date;
    private BigDecimal revenue;
    private Integer roomCount;
    private Integer occupiedCount;
    private Double occupancyRate;
    private BigDecimal avgPrice;
    private Integer customerCount;
    private Integer newCustomerCount;
    private Double repeatCustomerRate;
}
