package ua.edu.ukma.springers.voltstore.delivery.exceptions;

import lombok.Getter;

import java.util.UUID;

@Getter
public class ShipmentNotFoundException extends RuntimeException {
    private final UUID orderId;

    public ShipmentNotFoundException(UUID orderId) {
        super("Shipment not found for order " + orderId);
        this.orderId = orderId;
    }
}
