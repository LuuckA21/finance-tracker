package me.luucka.finance.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import me.luucka.finance.core.Currencies;

/**
 * The annotated string must be an ISO 4217 currency code. {@code null} is considered valid;
 * combine with {@code @NotNull} when required.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CurrencyCode.Validator.class)
public @interface CurrencyCode {

    String message() default "must be a valid ISO 4217 currency code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<CurrencyCode, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || Currencies.isValid(value);
        }
    }
}
