package ua.edu.ukma.springers.voltstore.delivery.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The carrier reported the parcel delivered; Order moves to {@code DELIVERED}. Emitted once per shipment. */
public record OrderDeliveredEvent(UUID eventId, UUID orderId, String ttn, Instant timestamp) implements ShipmentEvent {
    public static final String TYPE = "OrderDelivered";

    public OrderDeliveredEvent {
        Objects.requireNonNull(eventId, "eventId is mandatory");
        Objects.requireNonNull(orderId, "orderId is mandatory");
        Objects.requireNonNull(ttn, "ttn is mandatory");
        Objects.requireNonNull(timestamp, "timestamp is mandatory");
    }

    public static OrderDeliveredEvent of(UUID orderId, String ttn, Instant timestamp) {
        return new OrderDeliveredEvent(UUID.randomUUID(), orderId, ttn, timestamp);
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
