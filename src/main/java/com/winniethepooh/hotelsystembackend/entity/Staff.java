package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Staff {
    private Integer id;
    private Integer role;
    private String account;
    private String password;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
