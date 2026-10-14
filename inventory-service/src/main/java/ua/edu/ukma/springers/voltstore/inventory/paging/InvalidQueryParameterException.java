package ua.edu.ukma.springers.voltstore.inventory.paging;

import lombok.Getter;

@Getter
public class InvalidQueryParameterException extends RuntimeException {
    private final String parameter;

    public InvalidQueryParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }
}
