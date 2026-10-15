package ua.edu.ukma.springers.voltstore.payment.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The card's expiry month has not passed yet (a card is valid until the end of its expiry month). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotExpiredValidator.class)
public @interface NotExpired {
    String message() default "card has expired";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
