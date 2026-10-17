package ua.edu.ukma.springers.voltstore.order.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.clients.ClientsProperties;
import ua.edu.ukma.springers.voltstore.order.config.ExpiryProperties;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderCancelledEvent;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * EP-ORD-JOB-01: an order not paid within its window gives its reserved stock back and becomes {@code EXPIRED}.
 * <p>
 * <b>Grace period:</b> the pay endpoint refuses to start a charge after the window, and a charge takes at most the
 * Payment read timeout. The job waits longer than that after the window, so no charge can still be running when the
 * stock is released. If one is anyway (or a cancellation), the check-and-set fails and the order is left as it is.
 * <p>
 * <b>Release first:</b> a release without the status change is repeated harmlessly in the next run; an
 * {@code EXPIRED} order whose stock is still reserved would never be cleaned up.
 * <p>
 * <b>Several replicas</b> need no claim here: if two runs handle the same order, the second release does nothing and
 * the second check-and-set finds the order already {@code EXPIRED}.
 */
@Slf4j
@Component
public class PaymentWindowExpiryJob {
    private final OrderRepository orders;
    private final StockReservations stock;
    private final Outbox outbox;
    private final TransactionOperations transactions;
    private final ExpiryProperties properties;
    private final Clock clock;

    public PaymentWindowExpiryJob(OrderRepository orders, StockReservations stock, Outbox outbox,
                                  TransactionOperations transactions, ExpiryProperties properties,
                                  ClientsProperties clients, Clock clock) {
        if (properties.grace().compareTo(clients.payment().readTimeout()) <= 0) {
            throw new IllegalStateException("order.expiry.grace (" + properties.grace() + ") must be longer than "
                    + "clients.payment.read-timeout (" + clients.payment().readTimeout() + "): otherwise an order "
                    + "could expire while its charge is still running");
        }
        this.orders = orders;
        this.stock = stock;
        this.outbox = outbox;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${order.expiry.interval}", initialDelayString = "${order.expiry.interval}")
    public void expireDueOrders() {
        Instant now = clock.instant();
        Instant expiredBefore = now.minus(properties.grace());
        for (OrderEntity order : orders.findExpired(expiredBefore, Limit.of(properties.batchSize()))) {
            try {
                expire(order.getId(), expiredBefore, now);
            } catch (RuntimeException e) {
                log.error("Expiring order {} failed unexpectedly; trying again in the next run", order.getId(), e);
            }
        }
    }

    private void expire(UUID orderId, Instant expiredBefore, Instant now) {
        try {
            stock.release(orderId);
        } catch (RuntimeException e) {
            log.warn("Could not release the reservation of expired order {} ({}); trying again in the next run",
                    orderId, e.getMessage());
            return;
        }
        try {
            transactions.executeWithoutResult(tx -> {
                OrderEntity order = orders.findById(orderId).orElse(null);
                if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT
                        || order.getReservationExpiresAt().isAfter(expiredBefore)) {
                    log.info("Order {} was paid or cancelled meanwhile; not expiring it", orderId);
                    return;
                }
                order.expire();
                orders.saveAndFlush(order);
                outbox.append(OrderCancelledEvent.of(order, OrderCancelledEvent.Reason.EXPIRED, now));
                log.info("Order {} expired: not paid by {}", orderId, order.getReservationExpiresAt());
            });
        } catch (OptimisticLockingFailureException e) {
            log.info("Order {} changed while expiring it; leaving the other change", orderId);
        }
    }
}
