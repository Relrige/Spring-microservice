package ua.edu.ukma.springers.voltstore.catalog.utils;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.RequestValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs Bean Validation explicitly, for endpoints where the spec requires another check (e.g. "404 if the
 * resource does not exist") to happen before body validation. {@code @Valid} on the controller would
 * always run first.
 */
@Component
@RequiredArgsConstructor
public class RequestValidator {
    private final Validator validator;

    public void validate(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return;
        }
        Map<String, List<String>> errors = new TreeMap<>();
        for (ConstraintViolation<Object> violation : violations) {
            errors.computeIfAbsent(violation.getPropertyPath().toString(), field -> new ArrayList<>())
                    .add(violation.getMessage());
        }
        throw new RequestValidationException(errors);
    }
}
