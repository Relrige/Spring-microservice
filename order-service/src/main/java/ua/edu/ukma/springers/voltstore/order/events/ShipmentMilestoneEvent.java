package ua.edu.ukma.springers.voltstore.order.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Delivery's {@code TTNGenerated} and {@code OrderDelivered} (topic {@code delivery.shipment-events}, told apart by
 * the {@code eventType} header). Both have this payload. A tolerant reader: unknown fields are ignored.
 */
public record ShipmentMilestoneEvent(@NotNull UUID eventId, @NotNull UUID orderId, @NotBlank String ttn,
                                     @NotNull Instant timestamp) {
    public static final String TTN_GENERATED = "TTNGenerated";
    public static final String ORDER_DELIVERED = "OrderDelivered";
}
