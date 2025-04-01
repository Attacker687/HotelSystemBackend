package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

@Data
public class RegisterDTO {
    private String name;
    private String idCardNumber;
    private String phone;
    private String email;
    private String password;
}
