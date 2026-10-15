package ua.edu.ukma.springers.voltstore.payment.exceptions;

import lombok.Getter;

import java.util.UUID;

@Getter
public class NoSuccessfulChargeException extends RuntimeException {
    private final UUID orderId;

    public NoSuccessfulChargeException(UUID orderId) {
        super("No successful charge found for order " + orderId + " - cannot refund");
        this.orderId = orderId;
    }
}
