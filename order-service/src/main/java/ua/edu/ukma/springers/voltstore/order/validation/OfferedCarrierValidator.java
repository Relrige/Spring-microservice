package ua.edu.ukma.springers.voltstore.order.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import ua.edu.ukma.springers.voltstore.order.config.CheckoutProperties;

/** Created by Spring's validator factory, which injects the configured carriers. */
public class OfferedCarrierValidator implements ConstraintValidator<OfferedCarrier, String> {
    private final CheckoutProperties properties;

    public OfferedCarrierValidator(CheckoutProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isValid(String carrier, ConstraintValidatorContext context) {
        return carrier == null || properties.carriers().contains(carrier);
    }
}
