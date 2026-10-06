package ua.edu.ukma.springers.voltstore.catalog.exception;

public class InvalidProductDataException extends RuntimeException {
    public InvalidProductDataException(String message) {
        super(message);
    }
}
