package ua.edu.ukma.springers.voltstore.order.ports;

import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Delivery Service: shipment statuses and cancellation. The status is never stored on the order. */
public interface Shipments {

    /**
     * One entry per requested order, for any number of orders (fetched in chunks of 100).
     *
     * @throws DependencyUnavailableException Delivery could not answer
     */
    List<ShipmentInfo> find(Collection<UUID> orderIds);

    /**
     * Cancels the order's shipment and its TTN; also when none exists yet (Delivery stores a tombstone). Idempotent.
     *
     * @throws DependencyUnavailableException Delivery could not answer
     */
    void cancel(UUID orderId);

    enum DeliveryStatus {
        TTN_PENDING,
        TTN_FAILED,
        TTN_GENERATED,
        IN_TRANSIT,
        DELIVERED,
        CANCELLED
    }

    /**
     * @param status {@code null} when Delivery has no shipment for the order yet ({@code OrderPaid} not processed)
     *               or answered a status this version does not know
     */
    record ShipmentInfo(UUID orderId, DeliveryStatus status, String ttn, String failureReason) {
    }
}
