package ua.edu.ukma.springers.voltstore.order.exceptions;

/**
 * The first request with this {@code Idempotency-Key} is still running (D8); a retry after {@code Retry-After} gets
 * its result. Answered with 409.
 */
public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException() {
        super("A request with this Idempotency-Key is still being processed");
    }
}
