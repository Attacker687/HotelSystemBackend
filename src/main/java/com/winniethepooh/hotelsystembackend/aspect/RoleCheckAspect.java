package com.winniethepooh.hotelsystembackend.aspect;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.exception.ForbiddenException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RoleCheckAspect {

    // 方法上或类上标了 @RoleRequired 都拦截
    @Around("@within(com.winniethepooh.hotelsystembackend.annotation.RoleRequired)"
            + " || @annotation(com.winniethepooh.hotelsystembackend.annotation.RoleRequired)")
    public Object checkRole(ProceedingJoinPoint joinPoint) throws Throwable {
        Integer currentRole = BaseContext.getCurrentRole();
        if (currentRole == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "未登录或角色信息缺失");
        }

        int[] allowed = requiredRoles(joinPoint).value();
        for (int role : allowed) {
            if (role == currentRole) {
                return joinPoint.proceed(); // 放行
            }
        }

        throw new ForbiddenException("无权限访问该资源");
    }

    /** 先取方法上的注解，没有再取类上的 */
    private static RoleRequired requiredRoles(ProceedingJoinPoint joinPoint) {
        RoleRequired onMethod = AnnotationUtils.findAnnotation(
                ((MethodSignature) joinPoint.getSignature()).getMethod(), RoleRequired.class);
        if (onMethod != null) return onMethod;
        return AnnotationUtils.findAnnotation(AopUtils.getTargetClass(joinPoint.getTarget()), RoleRequired.class);
    }
}
