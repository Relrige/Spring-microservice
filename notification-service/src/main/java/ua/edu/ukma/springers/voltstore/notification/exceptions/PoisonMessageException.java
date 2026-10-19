package ua.edu.ukma.springers.voltstore.notification.exceptions;

/**
 * A consumed record that can never be processed (not JSON, or a required field missing or invalid). The Kafka error
 * handler does not retry it and sends it to the dead-letter topic at once. The message never contains the payload,
 * which holds email addresses.
 */
public class PoisonMessageException extends RuntimeException {
    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
