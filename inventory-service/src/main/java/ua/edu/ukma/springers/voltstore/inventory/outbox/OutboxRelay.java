package ua.edu.ukma.springers.voltstore.inventory.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox events to Kafka (polling relay; change data capture, e.g. Debezium reading the PostgreSQL WAL,
 * is the alternative that avoids polling but needs extra infrastructure).
 * <p>
 * <b>Delivery</b> is at-least-once: an event is marked published only after the broker acknowledged it, so a crash
 * (or a timed-out acknowledgment) between sending and marking re-sends it. Consumers deduplicate by
 * {@code eventId} or, like Catalog, are idempotent by state. A relay failure never affects the HTTP request that
 * created the event; it only delays it.
 * <p>
 * <b>Per-product order</b> (unlike Catalog's outbox, stock events of one product must arrive in order, e.g.
 * Depleted before Replenished):
 * <ul>
 *   <li>Only one relay publishes at a time across all replicas: each run holds a PostgreSQL transaction-level
 *   advisory lock; a replica that does not get it skips the run. Plain {@code FOR UPDATE SKIP LOCKED} batching is
 *   not enough: replica A could claim rows 1–2 and replica B row 3 of the same product, and B's send could
 *   overtake A's.</li>
 *   <li>The single relay sends events in {@code sequence_number} order and waits for each acknowledgment before
 *   the next send.</li>
 *   <li>For one product the sequence order is the commit order, because its events are written while its stock
 *   row is locked.</li>
 * </ul>
 * <b>Failures:</b> a failed event gets an exponential back-off ({@code next_attempt_at}) and is retried forever.
 * Until it succeeds, the later events of the same product are held back, while other products' events keep
 * flowing. The run stops at the first failure, because during a broker outage every further send would fail too,
 * each after a timeout. An operator sees an error log line per failed attempt (with how long the event has been
 * waiting) and the gauge {@code inventory.outbox.oldest.unpublished.age}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxRelay implements MeterBinder {
    // Identifies the relay's advisory lock; any constant that no other code of this database uses
    static final long RELAY_LOCK_KEY = 0x0B0C_0017L;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, Object> kafkaTemplate,
                       JsonMapper jsonMapper, OutboxProperties properties,
                       PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.relay-interval}")
    public void publishDueEvents() {
        // Keep going while full batches succeed, so a backlog drains without waiting for the next run
        boolean fullBatchPublished;
        do {
            fullBatchPublished = Boolean.TRUE.equals(transactionTemplate.execute(status -> publishBatch()));
        } while (fullBatchPublished);
    }

    /**
     * One transaction: take the relay lock, read a batch of due events, publish them one by one and record the
     * outcome. The lock is held until the transaction ends, so no other replica publishes meanwhile.
     *
     * @return whether a full batch was published, i.e. more events may be waiting
     */
    boolean publishBatch() {
        if (!repository.tryRelayLock(RELAY_LOCK_KEY)) {
            log.debug("Another relay instance is publishing outbox events; skipping this run");
            return false;
        }
        List<OutboxEvent> events = repository.findDue(Instant.now(), properties.batchSize());
        for (OutboxEvent event : events) {
            try {
                send(event);
                event.markPublished(Instant.now());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Instant now = Instant.now();
                Duration retryAfter = retryDelay(event.getAttempts());
                event.markFailed(now, retryAfter, e.toString());
                log.error("Outbox event {} ({} of product {}) failed to publish: attempt {}, unpublished for {}, "
                                + "next try in {}; later events of this product wait for it",
                        event.getId(), event.getEventType(), event.getAggregateId(), event.getAttempts(),
                        Duration.between(event.getCreatedAt(), now), retryAfter, e);
                return false;
            }
        }
        return events.size() == properties.batchSize();
    }

    // Published events are only needed for troubleshooting; keep them for the retention period
    @Scheduled(fixedDelayString = "${inventory.outbox.cleanup-interval}")
    @Transactional
    public int deletePublishedEvents() {
        int deleted = repository.deletePublishedBefore(Instant.now().minus(properties.retention()));
        if (deleted > 0) {
            log.info("Deleted {} published outbox events older than {}", deleted, properties.retention());
        }
        return deleted;
    }

    // 0 when nothing is waiting; grows while the broker is down or an event is stuck
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("inventory.outbox.oldest.unpublished.age", this, OutboxRelay::oldestUnpublishedAgeSeconds)
                .description("Age of the oldest stock event not yet published to Kafka")
                .baseUnit("seconds")
                .register(registry);
    }

    double oldestUnpublishedAgeSeconds() {
        return repository.findOldestUnpublishedCreatedAt()
                .map(createdAt -> Duration.between(createdAt, Instant.now()).toMillis() / 1000.0)
                .orElse(0.0);
    }

    private void send(OutboxEvent event) throws Exception {
        // Sent as a JSON tree, so the stored payload goes out byte-for-byte as JSON (not as a quoted string)
        ProducerRecord<String, Object> record = new ProducerRecord<>(event.getTopic(), event.getMessageKey(),
                jsonMapper.readTree(event.getPayload()));
        record.headers().add(KafkaTopics.EVENT_TYPE_HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaTopics.EVENT_ID_HEADER, event.getId().toString().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    // 1 s, 2 s, 4 s, ... capped at maxRetryDelay
    private Duration retryDelay(int previousAttempts) {
        Duration delay = Duration.ofSeconds(1L << Math.min(previousAttempts, 20));
        return delay.compareTo(properties.maxRetryDelay()) > 0 ? properties.maxRetryDelay() : delay;
    }
}
