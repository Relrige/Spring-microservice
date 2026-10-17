package ua.edu.ukma.springers.voltstore.order.exceptions;

/**
 * A customer placed an order without {@code X-User-Email}. The gateway always forwards it (decision D1), so this is a
 * broken gateway contract (or a token issued before the {@code email} claim existed), not a customer mistake.
 * Answered with 401: signing in again gives a token with the claim.
 */
public class MissingCustomerEmailException extends RuntimeException {

    public MissingCustomerEmailException() {
        super("The request has no X-User-Email header");
    }
}
