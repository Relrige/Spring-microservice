package ua.edu.ukma.springers.voltstore.delivery.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.delivery.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.delivery.events.ShipmentEvent;
import ua.edu.ukma.springers.voltstore.delivery.events.ShipmentEventPublisher;

import java.time.Instant;

/**
 * Transactional outbox for shipment events (copied from Inventory): {@link #publish} stores the event in
 * {@code outbox_events} as part of the caller's transaction, so the event exists if and only if the status change
 * was committed. {@link OutboxRelay} sends it to Kafka later.
 * <p>
 * <b>One transaction manager:</b> the shipment row and the outbox row are written through the service's single JPA
 * {@code TransactionManager} on one {@code DataSource}, so one local database transaction makes them atomic. There
 * are no Kafka transactions and no chained or XA transaction manager: Kafka is touched only by the relay, outside
 * the business transaction.
 */
@Component
@RequiredArgsConstructor
public class OutboxShipmentEventPublisher implements ShipmentEventPublisher {
    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;

    /**
     * The payload ({@code eventId, orderId, ttn, timestamp}) is serialized to JSON now, so later code changes to the
     * event classes do not affect events that are already waiting. Topic {@code delivery.shipment-events}, keyed by
     * orderId.
     */
    // MANDATORY: appending outside a transaction would silently re-create the dual-write problem
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(ShipmentEvent event) {
        repository.save(new OutboxEvent(event.eventId(), event.orderId(), event.eventType(),
                KafkaTopics.SHIPMENT_EVENTS, event.orderId().toString(), jsonMapper.writeValueAsString(event),
                Instant.now()));
    }
}
