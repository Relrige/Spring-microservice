package ua.edu.ukma.springers.voltstore.order.exceptions;

/**
 * The {@code Idempotency-Key} was already used by this customer for another request body (D8). Nothing was called or
 * created. Answered with 422.
 */
public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException() {
        super("This Idempotency-Key was already used for a different request; use a new key for a new order");
    }
}
