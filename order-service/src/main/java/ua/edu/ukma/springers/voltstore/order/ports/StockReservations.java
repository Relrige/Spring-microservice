package ua.edu.ukma.springers.voltstore.order.ports;

import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;

import java.util.List;
import java.util.UUID;

/** Inventory Service: stock reserved for an order, all or nothing. Both operations are idempotent per order. */
public interface StockReservations {

    /**
     * @throws DependencyUnavailableException Inventory could not answer. The reservation may or may not exist, so
     *                                        the caller releases it (release is idempotent)
     */
    ReservationResult reserve(UUID orderId, List<ReservationItem> items);

    /**
     * Returns the order's reserved stock; does nothing when nothing is reserved.
     *
     * @throws DependencyUnavailableException Inventory could not answer
     */
    void release(UUID orderId);

    record ReservationItem(UUID productId, int quantity) {
    }

    /** Inventory's answer: all items reserved, or none and the list of short items. */
    sealed interface ReservationResult {
    }

    /** Every item is reserved (also when an earlier call for the same order already reserved them). */
    record Reserved() implements ReservationResult {
    }

    /** Nothing was reserved; every short item is listed. */
    record Insufficient(List<InsufficientItem> insufficientItems) implements ReservationResult {
    }
}
