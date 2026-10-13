package ua.edu.ukma.springers.voltstore.catalog.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload of {@code ProductStockReplenished} and {@code ProductStockDepleted} (see the Event Catalogue); the
 * event type is in the {@code eventType} header. Unknown payload fields are ignored.
 *
 * @param productId the product whose available quantity crossed zero
 * @param timestamp when Inventory observed the change (informational; ordering comes from the partition)
 */
public record StockEvent(UUID productId, Instant timestamp) {
    public static final String REPLENISHED = "ProductStockReplenished";
    public static final String DEPLETED = "ProductStockDepleted";

    // A payload without productId can never be applied: failing here makes it a non-retryable
    // deserialization error that goes straight to the dead-letter topic
    public StockEvent {
        Objects.requireNonNull(productId, "productId must not be null");
    }
}
