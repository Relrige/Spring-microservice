package ua.edu.ukma.springers.voltstore.payment.exceptions;

/**
 * The gateway could not be reached or answered with a server error. The outcome is uncertain: the request may
 * have been processed. Answered with 502.
 */
public class PaymentGatewayUnavailableException extends RuntimeException {
    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
