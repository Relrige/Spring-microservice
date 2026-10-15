package ua.edu.ukma.springers.voltstore.delivery.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.events.ShipmentEventPublisher;
import ua.edu.ukma.springers.voltstore.delivery.events.TtnGeneratedEvent;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.InvalidShipmentStateException;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.ShipmentNotFoundException;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.TtnGenerationFailedException;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;

import java.time.Clock;
import java.util.UUID;

/**
 * EP-DEL-03, Tier 3: the worker retries a {@code TTN_FAILED} shipment after fixing the cause (usually the packaging
 * in Inventory). One synchronous attempt through the same {@link TtnRegistrationService} as the job, without back-off:
 * the worker is waiting for the answer. The packaging is always fetched again, because fixing it is the usual reason
 * for the retry.
 */
@Slf4j
@Service
public class ManualTtnRetryService {
    private final ShipmentRepository repository;
    private final TtnRegistrationService registrationService;
    private final ShipmentEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ManualTtnRetryService(ShipmentRepository repository, TtnRegistrationService registrationService,
                                 ShipmentEventPublisher eventPublisher, PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.repository = repository;
        this.registrationService = registrationService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @return the new TTN
     * @throws ShipmentNotFoundException     no shipment for the order (404)
     * @throws InvalidShipmentStateException the shipment is not {@code TTN_FAILED}, or changed during the attempt,
     *                                       e.g. a concurrent retry or cancel won (409)
     * @throws TtnGenerationFailedException  Inventory or the carrier rejected the attempt; the shipment stays
     *                                       {@code TTN_FAILED} with the new reason (424)
     */
    public String retry(UUID orderId) {
        ShipmentSnapshot snapshot = transactionTemplate.execute(status -> {
            Shipment shipment = repository.findByOrderId(orderId)
                    .orElseThrow(() -> new ShipmentNotFoundException(orderId));
            requireFailed(shipment);
            return ShipmentSnapshot.of(shipment);
        });

        // Outside any transaction: Inventory and carrier calls
        TtnAttemptResult result = registrationService.attempt(snapshot);

        boolean applied;
        try {
            applied = Boolean.TRUE.equals(transactionTemplate.execute(status -> apply(snapshot, result)));
        } catch (ObjectOptimisticLockingFailureException e) {
            applied = false;
        }
        if (!applied) {
            if (result instanceof TtnAttemptResult.Success success) {
                registrationService.cancelOrphanedTtn(snapshot, success.ttn());
            }
            throw new InvalidShipmentStateException(orderId, null,
                    "The shipment changed during the retry (a concurrent retry or cancellation); reload it");
        }
        return switch (result) {
            case TtnAttemptResult.Success success -> success.ttn();
            case TtnAttemptResult.Failure failure -> throw new TtnGenerationFailedException(orderId, failure.reason());
        };
    }

    // Only if the shipment is still TTN_FAILED with the version seen before the attempt (optimistic lock)
    private boolean apply(ShipmentSnapshot snapshot, TtnAttemptResult result) {
        Shipment shipment = repository.findById(snapshot.id()).orElseThrow();
        if (shipment.getStatus() != ShipmentStatus.TTN_FAILED || shipment.getVersion() != snapshot.version()) {
            return false;
        }
        switch (result) {
            case TtnAttemptResult.Success success -> {
                shipment.markGenerated(success.ttn(), success.parcel());
                eventPublisher.publish(TtnGeneratedEvent.of(shipment.getOrderId(), success.ttn(), clock.instant()));
                log.info("Manual retry generated TTN {} for order {}", success.ttn(), shipment.getOrderId());
            }
            case TtnAttemptResult.Failure failure -> {
                shipment.markFailed(failure.reason());
                log.warn("Manual TTN retry for order {} failed: {}", shipment.getOrderId(), failure.reason());
            }
        }
        return true;
    }

    private static void requireFailed(Shipment shipment) {
        if (shipment.getStatus() != ShipmentStatus.TTN_FAILED) {
            throw new InvalidShipmentStateException(shipment.getOrderId(), shipment.getStatus(),
                    "Retry is only allowed for shipments in TTN_FAILED status");
        }
    }
}
