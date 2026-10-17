package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;

/** The card was declined; the order is still payable with another card. Answered with 402 and Payment's reason. */
@Getter
public class PaymentDeclinedException extends RuntimeException {
    private final String reason;

    public PaymentDeclinedException(String reason) {
        super("Payment declined: " + reason);
        this.reason = reason;
    }
}
