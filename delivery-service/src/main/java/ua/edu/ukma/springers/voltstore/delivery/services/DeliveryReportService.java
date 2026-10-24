package ua.edu.ukma.springers.voltstore.delivery.services;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierEventHandler;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.events.OrderDeliveredEvent;
import ua.edu.ukma.springers.voltstore.delivery.events.ShipmentEventPublisher;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;

import java.time.Clock;


/**
 * EP-DEL-04, the inbound carrier port: the carrier reports a delivery by TTN. Called by the mock carrier on its
 * scheduler thread (not a request or listener thread), so it opens its own transaction and logging context.
 * <ul>
 *   <li>{@code IN_TRANSIT} → {@code DELIVERED} and {@code OrderDelivered} in the outbox, in one transaction;</li>
 *   <li>{@code DELIVERED} → nothing: carriers may report twice (restart recovery, several replicas), and the event
 *   is written only on the actual transition, so it is emitted once;</li>
 *   <li>unknown TTN or any other status → logged, no change.</li>
 * </ul>
 * Database errors propagate, so the carrier adapter reports again later.
 */
@Slf4j
@Service
public class DeliveryReportService implements CarrierEventHandler {
    private final ShipmentRepository repository;
    private final ShipmentEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ObservationRegistry observationRegistry;

    public DeliveryReportService(ShipmentRepository repository, ShipmentEventPublisher eventPublisher,
                                 PlatformTransactionManager transactionManager, Clock clock,
                                 ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void onDelivered(String ttn) {
        // One trace per report (observability task 02), continued by the OrderDelivered event it publishes
        Observation.createNotStarted("delivery.report", observationRegistry).observe(() ->
            transactionTemplate.executeWithoutResult(status -> {
                Shipment shipment = repository.findByTtn(ttn).orElse(null);
                if (shipment == null) {
                    log.warn("Delivery report for unknown TTN {}; ignored", ttn);
                    return;
                }
                switch (shipment.getStatus()) {
                    case IN_TRANSIT -> {
                        shipment.markDelivered();
                        eventPublisher.publish(OrderDeliveredEvent.of(shipment.getOrderId(), ttn, clock.instant()));
                        log.info("Order {} (TTN {}) delivered", shipment.getOrderId(), ttn);
                    }
                    case DELIVERED -> log.info("Repeated delivery report for order {} (TTN {}); nothing to do",
                            shipment.getOrderId(), ttn);
                    default -> log.warn("Delivery report for order {} (TTN {}) while the shipment is {}; ignored",
                            shipment.getOrderId(), ttn, shipment.getStatus());
                }
            }));
    }
}
