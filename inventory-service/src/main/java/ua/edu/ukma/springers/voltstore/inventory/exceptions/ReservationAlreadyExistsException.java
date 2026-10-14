package ua.edu.ukma.springers.voltstore.inventory.exceptions;

import lombok.Getter;

import java.util.UUID;

@Getter
public class ReservationAlreadyExistsException extends RuntimeException {
    private final UUID orderId;

    public ReservationAlreadyExistsException(UUID orderId) {
        super("Order " + orderId + " already has active reservations");
        this.orderId = orderId;
    }
}
