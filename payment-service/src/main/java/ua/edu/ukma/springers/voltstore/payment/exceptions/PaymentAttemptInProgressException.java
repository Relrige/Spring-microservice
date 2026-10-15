package ua.edu.ukma.springers.voltstore.payment.exceptions;

import lombok.Getter;

import java.util.UUID;

/**
 * The gateway refused the idempotency key because an earlier request with that key had other parameters (for example
 * another card after a timeout). That earlier request's outcome stands and is still unknown to us, so nothing new
 * may be started for the order until it is settled by repeating the same request. Answered with 409.
 */
@Getter
public class PaymentAttemptInProgressException extends RuntimeException {
    private final UUID orderId;

    public PaymentAttemptInProgressException(UUID orderId) {
        super("A previous payment request for order " + orderId
                + " is still being resolved; repeat it with the same details");
        this.orderId = orderId;
    }
}
