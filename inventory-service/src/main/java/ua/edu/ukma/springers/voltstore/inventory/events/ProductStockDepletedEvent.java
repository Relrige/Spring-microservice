package ua.edu.ukma.springers.voltstore.inventory.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code availableQuantity} of a product crossed from a positive value to 0.
 */
public record ProductStockDepletedEvent(UUID eventId, UUID productId, Instant timestamp) implements StockEvent {
    public static final String TYPE = "ProductStockDepleted";

    public ProductStockDepletedEvent {
        Objects.requireNonNull(eventId, "eventId is mandatory");
        Objects.requireNonNull(productId, "productId is mandatory");
        Objects.requireNonNull(timestamp, "timestamp is mandatory");
    }

    public static ProductStockDepletedEvent forProduct(UUID productId) {
        return new ProductStockDepletedEvent(UUID.randomUUID(), productId, Instant.now());
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
