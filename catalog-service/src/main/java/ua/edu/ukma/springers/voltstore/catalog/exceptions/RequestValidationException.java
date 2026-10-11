package ua.edu.ukma.springers.voltstore.catalog.exceptions;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Field validation failure detected by the service itself (instead of by {@code @Valid} on the controller),
 * used where the spec requires another check, such as existence, to run before body validation.
 */
@Getter
public class RequestValidationException extends RuntimeException {
    private final Map<String, List<String>> errors;

    public RequestValidationException(Map<String, List<String>> errors) {
        super("One or more fields are invalid.");
        this.errors = errors;
    }
}
