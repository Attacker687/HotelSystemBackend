package com.winniethepooh.hotelsystembackend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    /** 取值见 RoomTypeConstant：0 单人间、1 双人间、2 套房 */
    @NotNull(message = "房型不能为空")
    @Min(value = 0, message = "房型不合法")
    @Max(value = 2, message = "房型不合法")
    private Integer roomType;
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "价格必须大于 0")
    private BigDecimal price;
}
