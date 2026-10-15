package ua.edu.ukma.springers.voltstore.payment.exceptions;

/**
 * The gateway did not answer in time. The outcome is uncertain: the money may have been taken and only the answer
 * lost. Answered with 504.
 */
public class PaymentGatewayTimeoutException extends RuntimeException {
    public PaymentGatewayTimeoutException(String message) {
        super(message);
    }
}
