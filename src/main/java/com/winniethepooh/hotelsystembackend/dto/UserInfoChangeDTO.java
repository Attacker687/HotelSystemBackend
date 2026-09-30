package com.winniethepooh.hotelsystembackend.dto;

import cn.hutool.core.lang.RegexPool;
import com.winniethepooh.hotelsystembackend.annotation.Password;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UserInfoChangeDTO {
    private String phone;
    private String originPassword;
    @Pattern(regexp = RegexPool.EMAIL, flags = Pattern.Flag.CASE_INSENSITIVE, message = "请输入正确的邮箱")
    private String emailToChange;
    @Password
    private String passwordToChange;
}
