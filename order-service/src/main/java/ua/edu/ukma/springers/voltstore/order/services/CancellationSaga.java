package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.CancellationProperties;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderCancelledEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.Payments;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The cancellation saga (EP-ORD-06 step 4), orchestrated by Order. Used by the customer's {@code DELETE} and by the
 * job that finishes interrupted cancellations (EP-ORD-JOB-02).
 * <p>
 * The steps undo what earlier steps did in other services, in this order:
 * <ol>
 *   <li>Inventory: release the reservation (always; does nothing when nothing is reserved).</li>
 *   <li>Delivery: cancel the shipment, unless the order was never paid (Delivery stores a tombstone when there is no
 *   shipment yet, so a late {@code OrderPaid} creates nothing).</li>
 *   <li>Payment: refund the full amount, only if the order was paid before cancelling.</li>
 * </ol>
 * Every step is idempotent in the other service, so the whole saga is simply run again after a failure: a step that
 * already succeeded succeeds again without a second effect. <b>Nothing is rolled back</b> (decision D3): a failed
 * step leaves the order {@code CANCELLATION_REQUESTED} with the failure recorded, and the job tries again later. No
 * database transaction is open during the remote calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancellationSaga {
    // cancellation_failure column size
    private static final int MAX_FAILURE_LENGTH = 1000;

    private final OrderRepository orders;
    private final StockReservations stock;
    private final Shipments shipments;
    private final Payments payments;
    private final Outbox outbox;
    private final TransactionOperations transactions;
    private final CancellationProperties properties;
    private final Clock clock;

    /**
     * Runs every step for an order in {@code CANCELLATION_REQUESTED}.
     *
     * @return {@code CANCELLED} when every step succeeded, {@code CANCELLATION_REQUESTED} when one failed (recorded
     * on the order), or the order's status if it was no longer cancelling
     */
    public OrderStatus run(UUID orderId) {
        OrderEntity order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.CANCELLATION_REQUESTED) {
            return order.getStatus();
        }
        String failure = runSteps(order);
        try {
            return transactions.execute(tx -> failure == null ? finish(orderId) : recordFailure(orderId, failure));
        } catch (OptimisticLockingFailureException e) {
            // Another saga run changed the order meanwhile; its outcome stands
            log.warn("Order {} changed while its cancellation was being recorded; keeping the other change", orderId);
            return orders.findById(orderId).map(OrderEntity::getStatus).orElseThrow();
        }
    }

    /** @return {@code null} when every step succeeded, otherwise the failed step and its error */
    private String runSteps(OrderEntity order) {
        UUID orderId = order.getId();
        try {
            stock.release(orderId);
        } catch (RuntimeException e) {
            return "release: " + e.getMessage();
        }
        if (order.getStatusBeforeCancellation() != OrderStatus.PENDING_PAYMENT) {
            try {
                shipments.cancel(orderId);
            } catch (RuntimeException e) {
                return "delivery cancel: " + e.getMessage();
            }
        }
        if (order.refundOwed()) {
            Payments.RefundResult refund;
            try {
                refund = payments.refund(orderId, order.getTotalAmount(), order.getCurrency());
            } catch (RuntimeException e) {
                return "refund: " + e.getMessage();
            }
            if (refund instanceof Payments.RefundFailed failed) {
                return "refund: " + failed.cause();
            }
            // Refunded, or AlreadyRefunded by an earlier attempt whose answer was lost
        }
        return null;
    }

    private OrderStatus finish(UUID orderId) {
        OrderEntity order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.CANCELLATION_REQUESTED) {
            return order.getStatus();
        }
        order.markCancelled();
        orders.saveAndFlush(order);
        outbox.append(OrderCancelledEvent.of(order, OrderCancelledEvent.Reason.CUSTOMER_CANCELLED, clock.instant()));
        log.info("Order {} cancelled after {} failed attempts", orderId, order.getCancellationAttempts());
        return order.getStatus();
    }

    private OrderStatus recordFailure(UUID orderId, String failure) {
        OrderEntity order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.CANCELLATION_REQUESTED) {
            return order.getStatus();
        }
        boolean flaggedBefore = order.isNeedsManualAttention();
        String stored = failure.length() > MAX_FAILURE_LENGTH ? failure.substring(0, MAX_FAILURE_LENGTH) : failure;
        Instant now = clock.instant();
        order.recordCancellationFailure(stored, now, properties.manualAttentionAfter());
        orders.saveAndFlush(order);
        log.warn("Cancellation of order {} failed (attempt {}): {}; it will be retried", orderId,
                order.getCancellationAttempts(), stored);
        if (order.isNeedsManualAttention() && !flaggedBefore) {
            // The alert: once per order, when it is flagged
            log.error("Cancellation of order {} failed {} times and needs manual attention: {}", orderId,
                    order.getCancellationAttempts(), stored);
        }
        return order.getStatus();
    }
}
