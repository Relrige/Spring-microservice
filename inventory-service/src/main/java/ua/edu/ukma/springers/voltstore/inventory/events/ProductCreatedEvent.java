package ua.edu.ukma.springers.voltstore.inventory.events;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Inventory's own view of Catalog's {@code ProductCreated} (EP-INV-EVT-01); no shared event library. Unknown
 * payload fields are ignored by the message converter (tolerant reader).
 *
 * @param eventId the idempotency key (Catalog sends one per event; a redelivery repeats it)
 */
public record ProductCreatedEvent(UUID eventId, UUID productId, @Nullable Instant timestamp) {
    // Without these the event can never be applied (or deduplicated): failing during conversion makes it a
    // non-retryable poison message that goes straight to the dead-letter topic
    public ProductCreatedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
    }
}
