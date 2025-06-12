package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

@Data
public class UserLoginDTO {
    private String phone;
    private String password;
}
