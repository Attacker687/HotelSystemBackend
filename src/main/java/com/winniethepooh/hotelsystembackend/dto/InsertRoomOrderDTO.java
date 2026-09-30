package com.winniethepooh.hotelsystembackend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InsertRoomOrderDTO {
    private String name;
    private String phone;
    private String idCard;
    private String roomNumber;
    @NotNull(message = "入住时间不能为空")
    private LocalDateTime checkInTime;
    @NotNull(message = "离店时间不能为空")
    private LocalDateTime checkOutTime;
}
