package ua.edu.ukma.springers.voltstore.order.entities;

import java.util.Set;

/**
 * Lifecycle of an order (see docs/design/entities/order-state-machine.md). {@code DELIVERED}, {@code CANCELLED} and
 * {@code EXPIRED} are terminal.
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    PENDING_SHIPPING,
    SHIPPED,
    DELIVERED,
    CANCELLATION_REQUESTED,
    CANCELLED,
    EXPIRED;

    /** Statuses a customer may cancel from; cancellation is locked once the parcel is handed over. */
    public static final Set<OrderStatus> CANCELLABLE = Set.of(PENDING_PAYMENT, PAID, PENDING_SHIPPING);

    /** Paid orders waiting for dispatch: the fulfillment queue. */
    public static final Set<OrderStatus> AWAITING_DISPATCH = Set.of(PAID, PENDING_SHIPPING);
}
