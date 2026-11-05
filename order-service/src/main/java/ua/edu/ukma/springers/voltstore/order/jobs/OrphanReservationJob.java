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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        List<UUID> orphans = new ArrayList<>();
        for (ReservationAttempt attempt : attempts.findByCreatedAtLessThanEqualOrderByCreatedAtAsc(settledBefore,
                Limit.of(properties.batchSize()))) {
            UUID orderId = attempt.getOrderId();
            try {
                // The order's own transaction deletes the attempt; one left behind next to its order is only stale
                if (orders.existsById(orderId)) {
                    transactions.executeWithoutResult(tx -> attempts.deleteById(orderId));
                } else {
                    orphans.add(orderId);
                }
            } catch (RuntimeException e) {
                log.error("Cleaning up the reservation attempt of order {} failed unexpectedly", orderId, e);
            }
        }
        if (orphans.isEmpty()) {
            return;
        }

        // The whole batch on one gRPC stream (bidirectional); a failure is reported per order
        Map<UUID, String> failed;
        try {
            failed = stock.releaseAll(orphans);
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            failed = new LinkedHashMap<>();
            for (UUID orderId : orphans) {
                failed.put(orderId, reason);
            }
        }
        for (UUID orderId : orphans) {
            try {
                if (failed.containsKey(orderId)) {
                    recordFailedRelease(orderId, failed.get(orderId));
                } else {
                    transactions.executeWithoutResult(tx -> attempts.deleteById(orderId));
                    log.info("Released the reservation of order {}, which was never stored", orderId);
                }
            } catch (RuntimeException e) {
                log.error("Cleaning up the reservation attempt of order {} failed unexpectedly", orderId, e);
            }
        }
    }

    private void recordFailedRelease(UUID orderId, String reason) {
        transactions.executeWithoutResult(tx -> attempts.findById(orderId).ifPresent(attempt -> {
            attempt.recordFailedRelease(reason);
            if (attempt.getReleaseAttempts() == ALERT_AFTER) {
                log.error("The orphan reservation of order {} could not be released {} times; its stock stays "
                        + "reserved until Inventory is reachable: {}", orderId, ALERT_AFTER, attempt.getLastError());
            } else {
                log.warn("Could not release the orphan reservation of order {} (attempt {}): {}", orderId,
                        attempt.getReleaseAttempts(), attempt.getLastError());
            }
        }));
    }
}
