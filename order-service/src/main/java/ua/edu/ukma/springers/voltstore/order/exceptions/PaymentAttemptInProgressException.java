package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;

import java.util.UUID;

/**
 * Payment Service still has an unresolved earlier attempt for this order (made with another card). Repeating the
 * payment with the same card settles it. Answered with 409.
 */
@Getter
public class PaymentAttemptInProgressException extends RuntimeException {
    private final UUID orderId;

    public PaymentAttemptInProgressException(UUID orderId) {
        super("Your previous payment for order " + orderId
                + " is still being processed - try again with the same card");
        this.orderId = orderId;
    }
}
