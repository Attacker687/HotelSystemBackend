package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 某一晚的客房收入与售出间夜数（room_order_night 按 night 汇总）。 */
@Data
public class DailyRevenueDTO {
    private LocalDate date;
    private BigDecimal revenue;
    private Integer nightCount;
}
