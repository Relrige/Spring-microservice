package ua.edu.ukma.springers.voltstore.inventory.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * No two items of the annotated list may have the same product id. {@code null} lists, items and ids are
 * considered valid here; other constraints report them.
 */
@Documented
@Constraint(validatedBy = UniqueProductIdsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueProductIds {
    String message() default "Each product may appear only once";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
