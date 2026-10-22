package ua.edu.ukma.springers.voltstore.inventory.events;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Inventory's own view of Catalog's {@code ProductCreated} (EP-INV-EVT-01) and {@code ProductUpdated}
 * (EP-INV-EVT-03); no shared event library. Both have the same payload, and the {@code eventType} header tells them
 * apart. Unknown payload fields (e.g. {@code categoryId}, {@code basePrice}) are ignored by the message converter
 * (tolerant reader).
 * <p>
 * {@code title}, {@code status} and {@code version} are optional here, because a {@code ProductCreated} published
 * before Catalog's task 29 has none of them. {@code ProductUpdated} requires them, which the listener checks. The
 * status stays text, so a status Inventory does not know yet is not a conversion error.
 *
 * @param eventId the idempotency key of {@code ProductCreated} (Catalog sends one per event; a redelivery repeats it)
 * @param version Catalog's product version of this state; "newer version wins" when it is copied (D8)
 */
public record ProductEvent(UUID eventId, UUID productId, @Nullable String title, @Nullable String status,
                           @Nullable Long version, @Nullable Instant timestamp) {
    public static final String CREATED = "ProductCreated";
    public static final String UPDATED = "ProductUpdated";

    // Without these the event can never be applied (or deduplicated): failing during conversion makes it a
    // non-retryable poison message that goes straight to the dead-letter topic
    public ProductEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
    }
}
