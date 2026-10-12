package ua.edu.ukma.springers.voltstore.inventory.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Inventory's own view of Catalog's {@code ProductCreated} event (tolerant reader: no shared event library,
 * unknown properties are ignored). Only {@code productId} is needed; the other fields are optional.
 * A payload without {@code productId} fails conversion and is treated as a poison message.
 */
public record ProductCreatedEvent(UUID eventId, UUID productId, Instant timestamp) {
    public static final String TYPE = "ProductCreated";

    public ProductCreatedEvent {
        Objects.requireNonNull(productId, "productId is mandatory");
    }
}
