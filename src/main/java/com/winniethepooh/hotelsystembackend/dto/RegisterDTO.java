package com.winniethepooh.hotelsystembackend.dto;

import cn.hutool.core.lang.RegexPool;
import com.winniethepooh.hotelsystembackend.annotation.IdCard;
import com.winniethepooh.hotelsystembackend.annotation.Password;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterDTO {
    @NotBlank(message = "用户姓名不能为空")
    @Size(max = 16, message = "用户姓名不能超过16个字")
    private String name;
    @IdCard
    private String idCardNumber;
    @NotNull(message = "请输入正确的手机号")
    @Pattern(regexp = RegexPool.MOBILE, message = "请输入正确的手机号")
    private String phone;
    @NotNull(message = "请输入正确的邮箱")
    @Pattern(regexp = RegexPool.EMAIL, flags = Pattern.Flag.CASE_INSENSITIVE, message = "请输入正确的邮箱")
    private String email;
    @NotNull(message = "密码不能为空")
    @Password
    private String password;
}
