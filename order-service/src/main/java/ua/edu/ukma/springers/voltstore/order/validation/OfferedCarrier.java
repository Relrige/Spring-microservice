package ua.edu.ukma.springers.voltstore.order.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value is one of the carriers the shop offers ({@code order.checkout.carriers}). {@code null} is considered
 * valid here; {@code @NotBlank} reports it.
 */
@Documented
@Constraint(validatedBy = OfferedCarrierValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface OfferedCarrier {
    String message() default "Carrier is not offered";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
