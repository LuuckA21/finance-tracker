package me.luucka.finance.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * A date between 1900 and 2199. JSON accepts years like +999999, which PostgreSQL stores but no
 * dashboard can sensibly show; this keeps typos and hostile input out of the data.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ReasonableDate.Validator.class)
public @interface ReasonableDate {

    LocalDate MIN = LocalDate.of(1900, 1, 1);
    LocalDate MAX = LocalDate.of(2199, 12, 31);

    String message() default "must be between 1900-01-01 and 2199-12-31";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ReasonableDate, LocalDate> {
        @Override
        public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
            return value == null || (!value.isBefore(MIN) && !value.isAfter(MAX));
        }
    }
}
