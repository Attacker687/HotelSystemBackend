package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QueryUserVO {
    private int id;
    private String name;
    private String phone;
    private String email;
    private String idCardNumber;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
