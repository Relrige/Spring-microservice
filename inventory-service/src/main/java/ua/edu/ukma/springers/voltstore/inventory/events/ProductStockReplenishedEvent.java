package ua.edu.ukma.springers.voltstore.inventory.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code availableQuantity} of a product crossed from 0 to a positive value.
 */
public record ProductStockReplenishedEvent(UUID eventId, UUID productId, Instant timestamp) implements StockEvent {
    public static final String TYPE = "ProductStockReplenished";

    public ProductStockReplenishedEvent {
        Objects.requireNonNull(eventId, "eventId is mandatory");
        Objects.requireNonNull(productId, "productId is mandatory");
        Objects.requireNonNull(timestamp, "timestamp is mandatory");
    }

    public static ProductStockReplenishedEvent forProduct(UUID productId) {
        return new ProductStockReplenishedEvent(UUID.randomUUID(), productId, Instant.now());
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
