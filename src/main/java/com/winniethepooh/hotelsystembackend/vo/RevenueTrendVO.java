package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class RevenueTrendVO {
    private List<LocalDate> dates;
    private List<BigDecimal> revenue;
    private List<Double> occupancyRate;
}
