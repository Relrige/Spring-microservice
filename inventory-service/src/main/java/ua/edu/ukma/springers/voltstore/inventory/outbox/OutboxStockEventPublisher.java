package ua.edu.ukma.springers.voltstore.inventory.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;

import java.time.Instant;

/**
 * Transactional outbox for stock events: {@link #publish} stores the event in {@code outbox_events} as part of the
 * caller's transaction, so the event exists if and only if the stock change (and the reservation rows) was
 * committed. {@link OutboxRelay} sends it to Kafka later.
 * <p>
 * <b>One transaction manager:</b> the stock row, the reservation rows and the outbox row are all written through
 * the service's single JPA {@code TransactionManager} on one {@code DataSource}, so one local database transaction
 * makes them atomic. There are no Kafka transactions and no chained or XA transaction manager: Kafka is touched
 * only by the relay, outside the business transaction.
 */
@Component
@RequiredArgsConstructor
public class OutboxStockEventPublisher implements StockEventPublisher {
    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final OutboxTraceContext traceContext;

    /**
     * The payload ({@code eventId, productId, timestamp}) is serialized to JSON now, so later code changes to the
     * event classes do not affect events that are already waiting. Topic and key are the same as before the outbox:
     * {@code inventory.stock-events}, keyed by productId.
     */
    // MANDATORY: appending outside a transaction would silently re-create the dual-write problem
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(StockEvent event) {
        repository.save(new OutboxEvent(event.eventId(), event.productId(), event.eventType(),
                KafkaTopics.STOCK_EVENTS, event.productId().toString(), jsonMapper.writeValueAsString(event),
                Instant.now(), traceContext.current()));
    }
}
