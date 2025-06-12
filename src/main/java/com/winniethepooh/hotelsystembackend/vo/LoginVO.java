package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

@Data
public class LoginVO {
    private String token;
    private Integer id;
    private Integer role;
}
