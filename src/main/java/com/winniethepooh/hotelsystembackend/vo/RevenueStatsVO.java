package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RevenueStatsVO {
    private BigDecimal today;
    private Double todayChange;
    private BigDecimal month;
    private Double monthChange;
    private BigDecimal avgPrice;
    private Double avgPriceChange;
    private Double occupancyRate;
    private Double occupancyRateChange;
}
