package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

@Data
public class StaffRegisterDTO {
    private String account;
    private String password;
    private Integer role;
    private Integer status;
}
