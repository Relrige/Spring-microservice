package ua.edu.ukma.springers.voltstore.payment.exceptions;

import lombok.Getter;

import java.util.UUID;

@Getter
public class OrderAlreadyChargedException extends RuntimeException {
    private final UUID orderId;

    public OrderAlreadyChargedException(UUID orderId) {
        super("Order " + orderId + " has already been charged");
        this.orderId = orderId;
    }
}
