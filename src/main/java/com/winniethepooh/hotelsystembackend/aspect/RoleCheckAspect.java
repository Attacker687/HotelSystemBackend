package com.winniethepooh.hotelsystembackend.aspect;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.exception.ForbiddenException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RoleCheckAspect {

    @Around("@annotation(roleRequired)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RoleRequired roleRequired) throws Throwable {
        Integer currentRole = BaseContext.getCurrentRole();
        if (currentRole == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "未登录或角色信息缺失");
        }

        int[] allowed = roleRequired.value();
        for (int role : allowed) {
            if (role == currentRole) {
                return joinPoint.proceed(); // 放行
            }
        }

        throw new ForbiddenException("无权限访问该资源");
    }
}

