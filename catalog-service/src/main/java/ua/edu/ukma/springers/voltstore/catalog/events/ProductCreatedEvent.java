package ua.edu.ukma.springers.voltstore.catalog.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Event contract for {@code ProductCreated} (see the Event Catalogue). Published to
 * {@code catalog.product-events} with {@code productId} as the message key.
 *
 * @param eventId   unique per event, so consumers can deduplicate redelivered messages
 * @param productId the created product
 * @param timestamp when the product was created; serialized as an ISO-8601 UTC instant
 */
public record ProductCreatedEvent(UUID eventId, UUID productId, Instant timestamp) {
    public static final String EVENT_TYPE = "ProductCreated";

    public ProductCreatedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static ProductCreatedEvent forProduct(UUID productId) {
        return new ProductCreatedEvent(UUID.randomUUID(), productId, Instant.now());
    }
}
