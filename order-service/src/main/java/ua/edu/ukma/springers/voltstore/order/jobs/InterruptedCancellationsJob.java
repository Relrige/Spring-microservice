package ua.edu.ukma.springers.voltstore.order.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.CancellationProperties;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.services.CancellationSaga;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * EP-ORD-JOB-02: repeats the cancellation saga for orders left {@code CANCELLATION_REQUESTED} (decision D3), with
 * back-off, until it succeeds. A flagged order is retried too: a refund owed to a customer must not be dropped.
 * <p>
 * <b>Several replicas:</b> each order is claimed before its saga runs. The claim stores the attempt's start time in
 * a short transaction; two replicas that read the same version cannot both commit it, and the loser skips the
 * order. No lock is held during the remote calls, and the claim also restarts the back-off, so the order is not due
 * again while the attempt runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterruptedCancellationsJob {
    private final OrderRepository orders;
    private final CancellationSaga saga;
    private final TransactionOperations transactions;
    private final CancellationProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${order.cancellation.retry-interval}",
            initialDelayString = "${order.cancellation.retry-interval}")
    public void retryDueCancellations() {
        Instant now = clock.instant();
        for (OrderEntity candidate : orders.findInterruptedCancellations(Limit.of(properties.batchSize()))) {
            if (!isDue(candidate, now)) {
                log.debug("Cancellation of order {} is not due yet (last attempt {}, {} failed)", candidate.getId(),
                        candidate.getCancellationLastAttemptAt(), candidate.getCancellationAttempts());
                continue;
            }
            try {
                if (claim(candidate.getId(), now)) {
                    saga.run(candidate.getId());
                }
            } catch (RuntimeException e) {
                // One order never stops the run; the saga records its own failures, this is anything else
                log.error("Retrying the cancellation of order {} failed unexpectedly", candidate.getId(), e);
            }
        }
    }

    boolean isDue(OrderEntity order, Instant now) {
        Instant last = order.getCancellationLastAttemptAt();
        return last == null
                || !last.plus(properties.backoffAfter(order.getCancellationAttempts())).isAfter(now);
    }

    /** @return whether this run may attempt the order: still cancelling, still due, and not claimed by another run */
    private boolean claim(UUID orderId, Instant now) {
        try {
            return Boolean.TRUE.equals(transactions.execute(tx -> {
                OrderEntity order = orders.findById(orderId).orElse(null);
                if (order == null || order.getStatus() != OrderStatus.CANCELLATION_REQUESTED || !isDue(order, now)) {
                    return false;
                }
                order.startCancellationAttempt(now);
                orders.saveAndFlush(order);
                return true;
            }));
        } catch (OptimisticLockingFailureException e) {
            log.debug("Order {} was claimed by another run", orderId);
            return false;
        }
    }
}
