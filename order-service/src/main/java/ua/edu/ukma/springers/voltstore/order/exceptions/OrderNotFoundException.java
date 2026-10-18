package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;

import java.util.UUID;

/**
 * No such order for this caller. Also used for another customer's order, so customers cannot tell which order IDs
 * exist. Answered with 404.
 */
@Getter
public class OrderNotFoundException extends RuntimeException {
    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order " + orderId + " not found");
        this.orderId = orderId;
    }
}
