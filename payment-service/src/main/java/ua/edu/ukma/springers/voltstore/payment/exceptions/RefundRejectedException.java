package ua.edu.ukma.springers.voltstore.payment.exceptions;

import lombok.Getter;

import java.util.UUID;

/**
 * The gateway refused the refund. Answered with 502 (as the spec says), so that the cancellation saga treats it
 * like a provider failure and decides whether to retry or escalate.
 */
@Getter
public class RefundRejectedException extends RuntimeException {
    private final UUID orderId;

    public RefundRejectedException(UUID orderId) {
        super("The payment provider rejected the refund of order " + orderId);
        this.orderId = orderId;
    }
}
