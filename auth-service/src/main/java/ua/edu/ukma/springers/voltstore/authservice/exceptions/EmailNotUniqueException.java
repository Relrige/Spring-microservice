package ua.edu.ukma.springers.voltstore.authservice.exceptions;

public class EmailNotUniqueException extends RuntimeException {
    public EmailNotUniqueException() {
        super("Email already in use");
    }
}
