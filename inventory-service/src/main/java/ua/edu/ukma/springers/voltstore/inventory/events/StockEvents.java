package ua.edu.ukma.springers.voltstore.inventory.events;

import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.UUID;

@UtilityClass
public class StockEvents {
    /**
     * Returns the event for a change of the available quantity, or empty when it did not cross zero:
     * 0 to positive is {@code Replenished}, positive to 0 is {@code Depleted}; any other change
     * (positive to positive, 0 to 0) produces nothing.
     */
    public static Optional<StockEvent> forTransition(UUID productId, int before, int after) {
        if (before == 0 && after > 0) {
            return Optional.of(ProductStockReplenishedEvent.forProduct(productId));
        }
        if (before > 0 && after == 0) {
            return Optional.of(ProductStockDepletedEvent.forProduct(productId));
        }
        return Optional.empty();
    }

    /**
     * Maps the {@code eventType} header value to the payload class, so a consumer of the shared topic
     * can deserialize each record into the right event.
     *
     * @throws IllegalArgumentException for an unknown event type
     */
    public static Class<? extends StockEvent> classOf(String eventType) {
        return switch (eventType) {
            case ProductStockReplenishedEvent.TYPE -> ProductStockReplenishedEvent.class;
            case ProductStockDepletedEvent.TYPE -> ProductStockDepletedEvent.class;
            default -> throw new IllegalArgumentException("Unknown stock event type: " + eventType);
        };
    }
}
