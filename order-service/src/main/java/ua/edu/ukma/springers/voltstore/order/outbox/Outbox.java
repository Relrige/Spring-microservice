package ua.edu.ukma.springers.voltstore.order.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.order.events.OrderEvent;

import java.time.Instant;

/**
 * Transactional outbox for order events: {@link #append} stores the event in {@code outbox_events} as part of the
 * caller's transaction, so the event exists if and only if the order change was committed. {@link OutboxRelay}
 * sends it to Kafka later. Same design as Inventory's outbox.
 * <p>
 * <b>One transaction manager:</b> the order row and the outbox row are written through the service's single JPA
 * {@code TransactionManager} on one {@code DataSource}, so one local database transaction makes them atomic. Kafka is
 * touched only by the relay, outside the business transaction.
 */
@Component
public class Outbox {
    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final String topic;

    public Outbox(OutboxRepository repository, JsonMapper jsonMapper,
                  @Value("${order.kafka.topics.order-events}") String topic) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
    }

    /**
     * The payload is serialized to JSON now, so later code changes to the event records do not affect events that
     * are already waiting. Key = orderId: all events of one order go to one partition, in order.
     */
    // MANDATORY: appending outside a transaction would silently re-create the dual-write problem
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OrderEvent event) {
        repository.save(new OutboxEvent(event.eventId(), event.orderId(), event.eventType(), topic,
                event.orderId().toString(), jsonMapper.writeValueAsString(event), Instant.now()));
    }
}
