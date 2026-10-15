package ua.edu.ukma.springers.voltstore.delivery.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The carrier registered the parcel (automatically or by a manual retry); Order moves to {@code PENDING_SHIPPING}. */
public record TtnGeneratedEvent(UUID eventId, UUID orderId, String ttn, Instant timestamp) implements ShipmentEvent {
    public static final String TYPE = "TTNGenerated";

    public TtnGeneratedEvent {
        Objects.requireNonNull(eventId, "eventId is mandatory");
        Objects.requireNonNull(orderId, "orderId is mandatory");
        Objects.requireNonNull(ttn, "ttn is mandatory");
        Objects.requireNonNull(timestamp, "timestamp is mandatory");
    }

    public static TtnGeneratedEvent of(UUID orderId, String ttn, Instant timestamp) {
        return new TtnGeneratedEvent(UUID.randomUUID(), orderId, ttn, timestamp);
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
