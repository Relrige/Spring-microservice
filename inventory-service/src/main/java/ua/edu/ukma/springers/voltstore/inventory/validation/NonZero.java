package ua.edu.ukma.springers.voltstore.inventory.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated number must not be zero. {@code null} is considered valid; combine with {@code @NotNull}.
 * Supports {@code Byte}, {@code Short}, {@code Integer}, {@code Long}, {@code Float} and {@code Double}
 * (not {@code BigDecimal} or {@code BigInteger}).
 */
@Documented
@Constraint(validatedBy = NonZeroValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NonZero {
    String message() default "Value must be non-zero";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
