package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {
    private int id;
    private String name;
    private String phone;
    private String email;
    private String password;
    private String idCardNumber;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
