package ua.edu.ukma.springers.voltstore.inventory.events;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Inventory's own view of Order's {@code OrderShipped} (EP-INV-EVT-02). Other payload fields, such as
 * {@code customerEmail}, are ignored (tolerant reader).
 */
public record OrderShippedEvent(UUID orderId, @Nullable String ttn) {
    public OrderShippedEvent {
        Objects.requireNonNull(orderId, "orderId must not be null");
    }
}
