package ua.edu.ukma.springers.voltstore.delivery.exceptions;

import lombok.Getter;

// A query parameter that cannot be bound by Spring alone (e.g. a comma-separated list with limits); answered with 400
@Getter
public class InvalidQueryParameterException extends RuntimeException {
    private final String parameter;

    public InvalidQueryParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }
}
