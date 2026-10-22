package ua.edu.ukma.springers.voltstore.catalog.events;

import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload of both product events, {@code ProductCreated} and {@code ProductUpdated} (see the Event Catalogue):
 * the full state of the product after the change. Published to {@code catalog.product-events} with
 * {@code productId} as the message key; the {@code eventType} header tells the two types apart.
 * <p>
 * The relay does not guarantee order, so consumers keep the state with the highest {@code version} and ignore
 * older or repeated ones. {@code description} and {@code stockStatus} are left out on purpose (spec D6).
 *
 * @param eventId   unique per event, so consumers can deduplicate redelivered messages
 * @param version   the product's version after the change ({@code Product.version}); only "greater than" is
 *                  meaningful, values may skip
 * @param timestamp when the event was created; serialized as an ISO-8601 UTC instant
 */
public record ProductEvent(UUID eventId, UUID productId, String title, UUID categoryId, BigDecimal basePrice,
                           ProductStatus status, long version, Instant timestamp) {
    public static final String CREATED = "ProductCreated";
    public static final String UPDATED = "ProductUpdated";

    public ProductEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(basePrice, "basePrice must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    /** The product's current state. Call it after the change was flushed, so {@code version} is up to date. */
    public static ProductEvent of(Product product) {
        return new ProductEvent(UUID.randomUUID(), product.getId(), product.getTitle(), product.getCategoryId(),
                product.getBasePrice(), product.getStatus(), product.getVersion(), Instant.now());
    }
}
