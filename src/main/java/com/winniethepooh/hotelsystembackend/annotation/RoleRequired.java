package com.winniethepooh.hotelsystembackend.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RoleRequired {
    int[] value();  // 允许的角色列表
}
