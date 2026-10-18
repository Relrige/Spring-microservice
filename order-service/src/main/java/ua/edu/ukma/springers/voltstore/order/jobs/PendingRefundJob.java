package ua.edu.ukma.springers.voltstore.order.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.order.config.CancellationProperties;
import ua.edu.ukma.springers.voltstore.order.config.RecoveryProperties;
import ua.edu.ukma.springers.voltstore.order.entities.PendingRefund;
import ua.edu.ukma.springers.voltstore.order.repositories.PendingRefundRepository;
import ua.edu.ukma.springers.voltstore.order.services.OwedRefunds;

import java.time.Clock;
import java.time.Instant;

/**
 * Repeats refunds owed after a lost payment race ({@link PendingRefund}) with the cancellation's back-off (1, 5, 15,
 * then every 30 minutes) until Payment confirms them; after 5 failures the refund is flagged for a person and
 * retried anyway, because the money belongs to the customer.
 * <p>
 * <b>Several replicas</b> need no claim: two runs that refund the same order at once are harmless, because Payment
 * refunds an order at most once (its refund key is fixed per order) and answers the second with "already refunded".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingRefundJob {
    private final PendingRefundRepository pendingRefunds;
    private final OwedRefunds owedRefunds;
    private final RecoveryProperties recovery;
    private final CancellationProperties backoff;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${order.recovery.interval}", initialDelayString = "${order.recovery.interval}")
    public void retryDueRefunds() {
        Instant now = clock.instant();
        for (PendingRefund owed : pendingRefunds.findAllByOrderByLastAttemptAtAsc(Limit.of(recovery.batchSize()))) {
            if (!isDue(owed, now)) {
                continue;
            }
            try {
                owedRefunds.attempt(owed.getOrderId());
            } catch (RuntimeException e) {
                // One refund never stops the run; OwedRefunds records its own failures, this is anything else
                log.error("Retrying the refund of order {} failed unexpectedly", owed.getOrderId(), e);
            }
        }
    }

    boolean isDue(PendingRefund owed, Instant now) {
        return !owed.getLastAttemptAt().plus(backoff.backoffAfter(owed.getAttempts())).isAfter(now);
    }
}
