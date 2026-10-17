package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;

import java.util.UUID;

/** The payment window of the order has elapsed; no charge is attempted. Answered with 409. */
@Getter
public class PaymentWindowExpiredException extends RuntimeException {
    private final UUID orderId;

    public PaymentWindowExpiredException(UUID orderId) {
        super("Payment window expired - order " + orderId + " can no longer be paid");
        this.orderId = orderId;
    }
}
