package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.util.UUID;

/** The order's current status does not allow the requested change. Answered with 409. */
@Getter
public class InvalidOrderStateException extends RuntimeException {
    private final UUID orderId;
    private final OrderStatus status;

    public InvalidOrderStateException(UUID orderId, OrderStatus status, String action) {
        super("Order " + orderId + " cannot " + action + " in status " + status);
        this.orderId = orderId;
        this.status = status;
    }
}
