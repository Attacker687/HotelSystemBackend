package com.winniethepooh.hotelsystembackend.annotation;

import cn.hutool.core.util.IdcardUtil;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 身份证号（hutool IdcardUtil.isValidCard：18/15 位含校验位，及港澳台证件）。null 和空串都不合法。 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = IdCard.Validator.class)
public @interface IdCard {
    String message() default "请输入正确的身份证";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<IdCard, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            return IdcardUtil.isValidCard(value);
        }
    }
}
