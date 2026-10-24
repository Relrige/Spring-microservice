package ua.edu.ukma.springers.voltstore.catalog.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Entry point for domain code: records an event in the outbox as part of the caller's transaction, so the
 * event exists if and only if the business change was committed. {@link OutboxRelay} publishes it later.
 */
@Component
@RequiredArgsConstructor
public class Outbox {
    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final OutboxTraceContext traceContext;

    /**
     * @param eventId the payload's own eventId, reused as the outbox row id
     * @param payload serialized to JSON now, so later code changes to the payload class do not affect
     *                events that are already waiting
     */
    // MANDATORY: appending outside a transaction would silently re-create the dual-write problem
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, String eventType, String topic, UUID eventId,
                       Object payload) {
        repository.save(new OutboxEvent(eventId, aggregateType, aggregateId, eventType, topic,
                aggregateId.toString(), jsonMapper.writeValueAsString(payload), Instant.now(),
                traceContext.current()));
    }
}
