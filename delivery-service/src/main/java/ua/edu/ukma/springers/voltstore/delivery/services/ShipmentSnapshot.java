package ua.edu.ukma.springers.voltstore.delivery.services;

import ua.edu.ukma.springers.voltstore.delivery.carrier.Recipient;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * An immutable copy of what a TTN attempt needs, taken inside a transaction, so the attempt itself (Inventory and
 * carrier calls) runs without a transaction or an open persistence context. {@code version} is the optimistic-lock
 * version at the time of the copy: the result is applied only if the shipment still has it.
 */
public record ShipmentSnapshot(UUID id, UUID orderId, long version, String carrier, Recipient recipient,
                               List<ShipmentItem> items, BigDecimal declaredValue, String currency) {

    // Must be called inside a transaction: the items are loaded lazily
    public static ShipmentSnapshot of(Shipment shipment) {
        return new ShipmentSnapshot(shipment.getId(), shipment.getOrderId(), shipment.getVersion(),
                shipment.getCarrier(), shipment.getRecipient(), List.copyOf(shipment.getItems()),
                shipment.getDeclaredValue(), shipment.getCurrency());
    }
}
