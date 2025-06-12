package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StaffVO {
    private Integer id;
    private Integer role;
    private String account;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
