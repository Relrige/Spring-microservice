package ua.edu.ukma.springers.voltstore.authservice.exceptions;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
        super("Access denied");
    }
}
