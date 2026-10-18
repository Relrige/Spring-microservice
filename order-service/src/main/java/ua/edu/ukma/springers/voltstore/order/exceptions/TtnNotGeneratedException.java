package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;

import java.util.UUID;

/** The worker tried to dispatch an order whose shipment has no TTN yet. Answered with 409. */
@Getter
public class TtnNotGeneratedException extends RuntimeException {
    private final UUID orderId;

    public TtnNotGeneratedException(UUID orderId) {
        super("TTN must be generated before dispatching order " + orderId);
        this.orderId = orderId;
    }
}
