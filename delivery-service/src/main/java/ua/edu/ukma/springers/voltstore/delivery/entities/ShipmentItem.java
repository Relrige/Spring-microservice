package ua.edu.ukma.springers.voltstore.delivery.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;
import java.util.UUID;

/**
 * One order line of a shipment. Kept so the packaging can be fetched again from Inventory on every attempt; the
 * packaging itself is never stored per item.
 */
@Embeddable
public record ShipmentItem(
        @Column(name = "product_id", nullable = false) UUID productId,
        @Column(name = "quantity", nullable = false) int quantity
) {
    public ShipmentItem {
        Objects.requireNonNull(productId, "productId must not be null");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
    }
}
