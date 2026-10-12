package ua.edu.ukma.springers.voltstore.inventory.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NonZeroValidator implements ConstraintValidator<NonZero, Number> {
    @Override
    public boolean isValid(Number value, ConstraintValidatorContext context) {
        return value == null || value.doubleValue() != 0;
    }
}
