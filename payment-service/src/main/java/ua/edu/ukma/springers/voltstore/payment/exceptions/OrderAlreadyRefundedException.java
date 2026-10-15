package ua.edu.ukma.springers.voltstore.payment.exceptions;

import lombok.Getter;

import java.util.UUID;

@Getter
public class OrderAlreadyRefundedException extends RuntimeException {
    private final UUID orderId;

    public OrderAlreadyRefundedException(UUID orderId) {
        super("Order " + orderId + " has already been refunded");
        this.orderId = orderId;
    }
}
