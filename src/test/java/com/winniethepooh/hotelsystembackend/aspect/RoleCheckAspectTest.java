package com.winniethepooh.hotelsystembackend.aspect;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.exception.ForbiddenException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** S1：切面同时认方法上和类上的 @RoleRequired，方法上的优先；S2：没有身份时直接拒绝。 */
class RoleCheckAspectTest {

    @RoleRequired({RoleConstant.MANAGER})
    static class StubController {
        @RoleRequired({RoleConstant.FRONT})
        public String frontOnly() { return "ok"; }

        public String inheritsClassRole() { return "ok"; }
    }

    static class PlainStub {
        @RoleRequired({RoleConstant.MANAGER})
        public String managerOnly() { return "ok"; }
    }

    private final RoleCheckAspect aspect = new RoleCheckAspect();

    @AfterEach
    void clear() {
        BaseContext.clear();
    }

    private static ProceedingJoinPoint joinPoint(Object target, String method) throws Throwable {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(target.getClass().getMethod(method));
        when(jp.getSignature()).thenReturn(sig);
        when(jp.getTarget()).thenReturn(target);
        when(jp.proceed()).thenReturn("ok");
        return jp;
    }

    @Test
    void tc001_methodAnnotationWinsOverClassAnnotation_frontAllowed() throws Throwable {
        ProceedingJoinPoint jp = joinPoint(new StubController(), "frontOnly");
        BaseContext.setCurrentRole(RoleConstant.FRONT);

        assertThat(aspect.checkRole(jp)).isEqualTo("ok");
        verify(jp, times(1)).proceed();
    }

    @Test
    void tc002_methodAnnotationWinsOverClassAnnotation_managerRejected() throws Throwable {
        ProceedingJoinPoint jp = joinPoint(new StubController(), "frontOnly");
        BaseContext.setCurrentRole(RoleConstant.MANAGER);

        assertThatThrownBy(() -> aspect.checkRole(jp)).isInstanceOf(ForbiddenException.class);
        verify(jp, never()).proceed();
    }

    @Test
    void tc003_missingRoleIsRejected() throws Throwable {
        ProceedingJoinPoint jp = joinPoint(new PlainStub(), "managerOnly");
        BaseContext.clear();
        assertThat(BaseContext.getCurrentRole()).isNull();

        assertThatThrownBy(() -> aspect.checkRole(jp)).isInstanceOf(BusinessException.class);
        verify(jp, never()).proceed();
    }

    @Test
    void tc010_classAnnotationAppliesWhenMethodHasNone() throws Throwable {
        ProceedingJoinPoint jp = joinPoint(new StubController(), "inheritsClassRole");
        BaseContext.setCurrentRole(RoleConstant.USER);
        assertThatThrownBy(() -> aspect.checkRole(jp)).isInstanceOf(ForbiddenException.class);

        BaseContext.setCurrentRole(RoleConstant.MANAGER);
        assertThat(aspect.checkRole(jp)).isEqualTo("ok");
        verify(jp, times(1)).proceed();
    }
}
