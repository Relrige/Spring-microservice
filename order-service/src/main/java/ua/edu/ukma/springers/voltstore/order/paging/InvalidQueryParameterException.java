package ua.edu.ukma.springers.voltstore.order.paging;

import lombok.Getter;

/** A query parameter is out of range. Answered with 400 naming the parameter (same shape as Inventory's). */
@Getter
public class InvalidQueryParameterException extends RuntimeException {
    private final String parameter;

    public InvalidQueryParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }
}
