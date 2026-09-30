package com.winniethepooh.hotelsystembackend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class DynamicUpdatePriceDTO {
    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;
    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;
    private Integer roomType;
    private BigDecimal price;
}
