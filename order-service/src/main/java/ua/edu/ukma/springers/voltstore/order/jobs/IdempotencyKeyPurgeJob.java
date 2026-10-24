package ua.edu.ukma.springers.voltstore.order.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.RecoveryProperties;
import ua.edu.ukma.springers.voltstore.order.entities.IdempotencyKey;
import ua.edu.ukma.springers.voltstore.order.repositories.IdempotencyKeyRepository;

import java.time.Clock;
import java.time.Instant;

/**
 * EP-ORD-JOB-05 (D10): deletes Idempotency-Key claims still in progress after the settle time, left by an instance
 * that died mid-request (until then they answer {@code 409}), and completed keys past their TTL (the order stays,
 * only the key is forgotten). A reservation of such a dead request is released by {@link OrphanReservationJob}
 * through its own attempt row, not here.
 * <p>
 * <b>Several replicas</b> need no claim: deleting the same rows twice is harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyKeyPurgeJob {
    private final IdempotencyKeyRepository keys;
    private final TransactionOperations transactions;
    private final RecoveryProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${order.recovery.interval}", initialDelayString = "${order.recovery.interval}")
    public void purge() {
        Instant now = clock.instant();
        // Each rule on its own, so a failure of one does not stop the other
        purge(IdempotencyKey.Status.IN_PROGRESS, now.minus(properties.reservationSettle()), "stale claims");
        purge(IdempotencyKey.Status.COMPLETED, now.minus(properties.idempotencyKeyTtl()), "expired keys");
    }

    private void purge(IdempotencyKey.Status status, Instant createdBefore, String what) {
        try {
            Integer deleted = transactions.execute(tx -> keys.deleteByStatusCreatedBefore(status.name(),
                    createdBefore));
            log.debug("Purged {} Idempotency-Key {} created before {}", deleted, what, createdBefore);
        } catch (RuntimeException e) {
            log.warn("Purging Idempotency-Key {} failed; the next run retries: {}", what, e.getMessage());
        }
    }
}
