package ua.edu.ukma.springers.voltstore.delivery.exceptions;

import lombok.Getter;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;

import java.util.UUID;

/** A state change the shipment's current status does not allow (see the lifecycle in the spec). */
@Getter
public class InvalidShipmentStateException extends RuntimeException {
    private final UUID orderId;
    private final ShipmentStatus status;

    public InvalidShipmentStateException(UUID orderId, ShipmentStatus status, String message) {
        super(message);
        this.orderId = orderId;
        this.status = status;
    }
}
