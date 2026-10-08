package ua.edu.ukma.springers.voltstore.authservice.exceptions;

public class InvalidUserRoleException extends RuntimeException {
    public InvalidUserRoleException(String message) {
        super(message);
    }
}
