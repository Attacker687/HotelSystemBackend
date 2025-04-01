package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

@Data
public class UserInfoChangeDTO {
    private String phone;
    private String originPassword;
    private String emailToChange;
    private String passwordToChange;
}
