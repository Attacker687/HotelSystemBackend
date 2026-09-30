package com.winniethepooh.hotelsystembackend.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

/**
 * 住客密码规则（注册、改密码共用）：8–20 位，包含大小写字母、数字和特殊字符。
 * 按「过短 → 过长 → 字符组成」顺序只报第一条不满足的规则。null 视为合法，必填另加 @NotNull。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Password.Validator.class)
public @interface Password {
    String message() default "密码必须包含大小写字母、数字和特殊字符";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<Password, String> {
        private static final Pattern RULE = Pattern.compile(
                "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-={}\\[\\]:;\"'<>,.?/~`|\\\\]).{8,20}$");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            if (value == null) return true;
            String broken = value.length() < 8 ? "密码不得少于8位"
                    : value.length() > 20 ? "密码不得多于20位"
                    : RULE.matcher(value).matches() ? null : ctx.getDefaultConstraintMessageTemplate();
            if (broken == null) return true;
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(broken).addConstraintViolation();
            return false;
        }
    }
}
