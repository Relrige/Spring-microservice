package ua.edu.ukma.springers.voltstore.inventory.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An event about a product's stock crossing zero. All stock events share one topic and are keyed by
 * {@code productId} so that events of one product keep their order.
 */
public sealed interface StockEvent permits ProductStockReplenishedEvent, ProductStockDepletedEvent {
    UUID eventId();

    UUID productId();

    Instant timestamp();

    /**
     * Sent as the {@code eventType} Kafka header: the two payloads have the same shape, so a consumer
     * of the shared topic needs this to tell them apart.
     */
    String eventType();
}
