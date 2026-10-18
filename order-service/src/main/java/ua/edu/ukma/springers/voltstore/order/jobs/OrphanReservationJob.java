package ua.edu.ukma.springers.voltstore.order.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.RecoveryProperties;
import ua.edu.ukma.springers.voltstore.order.entities.ReservationAttempt;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.repositories.ReservationAttemptRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Releases stock reserved for orders that were never stored ({@link ReservationAttempt}): the save failed, the
 * instance died between the reservation and the save, or Inventory reserved after Order had given up and released.
 * Only attempts older than the settle time are touched, so every reservation call for them has finished and a
 * release cannot overtake a late reservation.
 * <p>
 * <b>Several replicas</b> need no claim: release is idempotent in Inventory, and deleting an attempt twice is a
 * no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanReservationJob {
    // A release failing this often (Inventory down for a long time) is worth a person's look
    private static final int ALERT_AFTER = 5;

    private final ReservationAttemptRepository attempts;
    private final OrderRepository orders;
    private final StockReservations stock;
    private final TransactionOperations transactions;
    private final RecoveryProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${order.recovery.interval}", initialDelayString = "${order.recovery.interval}")
    public void releaseOrphanReservations() {
        Instant settledBefore = clock.instant().minus(properties.reservationSettle());
        for (ReservationAttempt attempt : attempts.findByCreatedAtLessThanEqualOrderByCreatedAtAsc(settledBefore,
                Limit.of(properties.batchSize()))) {
            try {
                cleanUp(attempt.getOrderId());
            } catch (RuntimeException e) {
                log.error("Cleaning up the reservation attempt of order {} failed unexpectedly", attempt.getOrderId(),
                        e);
            }
        }
    }

    private void cleanUp(UUID orderId) {
        // The order's own transaction deletes the attempt; one left behind next to its order is only stale
        if (orders.existsById(orderId)) {
            transactions.executeWithoutResult(tx -> attempts.deleteById(orderId));
            return;
        }
        try {
            stock.release(orderId);
        } catch (RuntimeException e) {
            transactions.executeWithoutResult(tx -> attempts.findById(orderId).ifPresent(attempt -> {
                attempt.recordFailedRelease(e.getMessage() == null ? e.toString() : e.getMessage());
                if (attempt.getReleaseAttempts() == ALERT_AFTER) {
                    log.error("The orphan reservation of order {} could not be released {} times; its stock stays "
                            + "reserved until Inventory is reachable: {}", orderId, ALERT_AFTER, attempt.getLastError());
                } else {
                    log.warn("Could not release the orphan reservation of order {} (attempt {}): {}", orderId,
                            attempt.getReleaseAttempts(), attempt.getLastError());
                }
            }));
            return;
        }
        transactions.executeWithoutResult(tx -> attempts.deleteById(orderId));
        log.info("Released the reservation of order {}, which was never stored", orderId);
    }
}
