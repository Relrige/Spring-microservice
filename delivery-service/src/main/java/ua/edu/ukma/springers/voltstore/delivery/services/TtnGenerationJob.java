package ua.edu.ukma.springers.voltstore.delivery.services;

import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.events.ShipmentEventPublisher;
import ua.edu.ukma.springers.voltstore.delivery.events.TtnGeneratedEvent;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static ua.edu.ukma.springers.voltstore.delivery.filters.CorrelationIdKeys.CORRELATION_ID_MDC_KEY;

/**
 * Automatic TTN generation (Tier 1 and Tier 2). The {@code OrderPaid} consumer only creates {@code TTN_PENDING}
 * shipments; this job does the carrier work, so a slow carrier never blocks a Kafka partition.
 * <ol>
 *   <li><b>Claim</b> due shipments ({@code TTN_PENDING}, {@code next_attempt_at <= now}, oldest first) with
 *   {@code FOR UPDATE SKIP LOCKED}, move their {@code next_attempt_at} forward by a lease and commit, before any
 *   remote call. Another replica (or a second run) skips the locked rows and, after the commit, sees them as not
 *   due.</li>
 *   <li><b>Attempt</b> each one outside any transaction ({@link TtnRegistrationService}).</li>
 *   <li><b>Apply</b> the result in a new transaction, only if the shipment still is {@code TTN_PENDING} with the
 *   version seen at the claim (optimistic lock): success → {@code TTN_GENERATED} and {@code TTNGenerated} in the
 *   outbox; retryable failure → one more attempt after the next back-off, or {@code TTN_FAILED} when the back-offs
 *   are used up; non-retryable failure → {@code TTN_FAILED} at once. If the shipment changed meanwhile (it was
 *   cancelled), the result is discarded and a TTN obtained by the attempt is cancelled at the carrier.</li>
 * </ol>
 * <b>Why database-driven retries</b> instead of retries in the Kafka listener or a Resilience4j {@code Retry}: the
 * back-offs are minutes long. Waiting inside the listener would block the partition and could exceed the poll
 * interval, and an in-memory retry is lost on a restart. Resilience4j {@code Retry} fits short in-request retries
 * (as in Catalog's Inventory client), not retries minutes apart that must survive restarts. Here the state lives in
 * {@code attempts} / {@code next_attempt_at}, and {@code TTN_PENDING} is visible to the fulfillment queue meanwhile.
 * <p>
 * A paid order is never cancelled because of a carrier failure (SCN-S05): {@code TTN_FAILED} waits for the
 * worker's manual retry.
 * <p>
 * <b>Metric</b> {@code voltstore_ttn_job_seconds} ({@link LongTaskTimer}): a run can last long (a backlog of slow
 * carrier calls), and a plain Timer records a duration only when the run has ended, so a hung run would be
 * invisible. A LongTaskTimer reports runs <i>in progress</i>: {@code _count} is how many are running, {@code _sum}
 * their total duration so far and {@code _max} how long the oldest one has been going, which the alert
 * {@code TtnJobRunningTooLong} watches.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "delivery.ttn-job", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TtnJobProperties.class)
public class TtnGenerationJob {
    private final ShipmentRepository repository;
    private final TtnRegistrationService registrationService;
    private final ShipmentEventPublisher eventPublisher;
    private final TtnJobProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final LongTaskTimer runTimer;

    public TtnGenerationJob(ShipmentRepository repository, TtnRegistrationService registrationService,
                            ShipmentEventPublisher eventPublisher, TtnJobProperties properties,
                            PlatformTransactionManager transactionManager, Clock clock, MeterRegistry registry) {
        this.repository = repository;
        this.registrationService = registrationService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.runTimer = LongTaskTimer.builder("voltstore.ttn.job")
                .description("Runs of the automatic TTN generation job that are in progress")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${delivery.ttn-job.interval}")
    public void run() {
        runTimer.record(() -> {
            // Keep going while full batches are claimed, so a backlog drains without waiting for the next run
            while (runOnce() == properties.batchSize()) {
                log.debug("Full batch of TTN attempts processed; claiming the next one");
            }
        });
    }

    /** One claim and the attempts of the claimed shipments; returns how many were claimed. */
    public int runOnce() {
        List<ShipmentSnapshot> claimed = claimDueShipments();
        for (ShipmentSnapshot shipment : claimed) {
            // Outgoing Inventory calls carry this ID, so one attempt can be followed across both services' logs
            MDC.put(CORRELATION_ID_MDC_KEY, UUID.randomUUID().toString());
            try {
                process(shipment);
            } catch (RuntimeException e) {
                // E.g. the database is down while applying the result: the lease expires and the attempt is redone
                log.error("TTN attempt for order {} could not be completed; it is retried after the lease",
                        shipment.orderId(), e);
            } finally {
                MDC.remove(CORRELATION_ID_MDC_KEY);
            }
        }
        return claimed.size();
    }

    /**
     * Known gap (spec, Open Issues): if this instance dies after the carrier issued a TTN but before the result is
     * stored, the lease expires and another attempt registers a second TTN. Harmless with the mock carrier; a real
     * carrier would need an idempotency key (the orderId) on registration.
     */
    private List<ShipmentSnapshot> claimDueShipments() {
        return transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<Shipment> due = repository.findDueForTtnAttemptSkipLocked(now, properties.batchSize());
            if (due.isEmpty()) {
                return List.of();
            }
            repository.moveNextAttemptAt(due.stream().map(Shipment::getId).toList(), now.plus(properties.lease()));
            return due.stream().map(ShipmentSnapshot::of).toList();
        });
    }

    private void process(ShipmentSnapshot shipment) {
        TtnAttemptResult result = registrationService.attempt(shipment);
        boolean applied;
        try {
            applied = Boolean.TRUE.equals(transactionTemplate.execute(status -> apply(shipment, result)));
        } catch (ObjectOptimisticLockingFailureException e) {
            // Changed between our read and our commit
            applied = false;
        }
        if (!applied) {
            log.warn("Order {} changed during its TTN attempt (e.g. cancelled); the result is discarded",
                    shipment.orderId());
            if (result instanceof TtnAttemptResult.Success success) {
                registrationService.cancelOrphanedTtn(shipment, success.ttn());
            }
        }
    }

    private boolean apply(ShipmentSnapshot snapshot, TtnAttemptResult result) {
        Shipment shipment = repository.findById(snapshot.id()).orElseThrow();
        if (shipment.getStatus() != ShipmentStatus.TTN_PENDING || shipment.getVersion() != snapshot.version()) {
            return false;
        }
        Instant now = clock.instant();
        switch (result) {
            case TtnAttemptResult.Success success -> {
                shipment.markGenerated(success.ttn(), success.parcel());
                eventPublisher.publish(TtnGeneratedEvent.of(shipment.getOrderId(), success.ttn(), now));
                log.info("TTN {} generated for order {} (attempt {})", success.ttn(), shipment.getOrderId(),
                        shipment.getAttempts() + 1);
            }
            case TtnAttemptResult.Failure failure when failure.retryable()
                    && shipment.getAttempts() < properties.backoff().size() -> {
                Duration backoff = properties.backoff().get(shipment.getAttempts());
                shipment.recordFailedAttempt(failure.reason(), now.plus(backoff));
                log.warn("TTN attempt {} for order {} failed: {}; next attempt at {}", shipment.getAttempts(),
                        shipment.getOrderId(), failure.reason(), shipment.getNextAttemptAt());
            }
            case TtnAttemptResult.Failure failure -> {
                shipment.markFailed(failure.reason());
                log.error("TTN generation for order {} failed after {} attempts: {}; the shipment is TTN_FAILED and "
                                + "waits for a manual retry", shipment.getOrderId(), shipment.getAttempts(),
                        failure.reason());
            }
        }
        return true;
    }
}
