package com.winniethepooh.hotelsystembackend.aspect;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.nio.file.AccessDeniedException;

@Aspect
@Component
public class RoleCheckAspect {

    @Around("@annotation(roleRequired)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RoleRequired roleRequired) throws Throwable {
        Integer currentRole = BaseContext.getCurrentRole();
        if (currentRole == null) {
            throw new AccessDeniedException("未登录或角色信息缺失");
        }

        int[] allowed = roleRequired.value();
        for (int role : allowed) {
            if (role == currentRole) {
                return joinPoint.proceed(); // 放行
            }
        }

        throw new AccessDeniedException("无权限访问该资源");
    }
}

