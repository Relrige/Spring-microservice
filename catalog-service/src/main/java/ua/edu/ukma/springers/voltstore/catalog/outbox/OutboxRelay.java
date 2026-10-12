package ua.edu.ukma.springers.voltstore.catalog.outbox;

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
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox events to Kafka (polling relay; change data capture, e.g. Debezium reading the PostgreSQL
 * WAL, is the alternative that avoids polling but needs extra infrastructure).
 * <p>
 * Delivery is at-least-once: an event is marked published only after the broker acknowledged it, so a crash
 * (or a timed-out acknowledgment) between sending and marking re-sends it. Consumers deduplicate by
 * {@code eventId}. A relay failure never affects the HTTP request that created the event; it only delays it.
 * <p>
 * Failures: an event that cannot be published gets an exponential back-off ({@code next_attempt_at}) and is
 * retried forever, with an error log line showing how long it has been waiting (the signal that something
 * is stuck). While it waits, newer events are published, so one poison event never blocks the rest. This can
 * reorder events of the same aggregate; with {@code ProductCreated} as the only event type that cannot
 * happen, but a second event type per product would need "skip later events of a failing aggregate".
 */
@Slf4j
@Component
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxRelay {
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

    @Scheduled(fixedDelayString = "${catalog.outbox.relay-interval}")
    public void publishDueEvents() {
        // Keep going while full batches succeed, so a backlog drains without waiting for the next run
        boolean fullBatchPublished;
        do {
            fullBatchPublished = Boolean.TRUE.equals(transactionTemplate.execute(status -> publishBatch()));
        } while (fullBatchPublished);
    }

    /**
     * One transaction: claim (lock) a batch, publish each event, record the outcome. Stops at the first
     * failure, because with a broker outage every further send would fail too, each after a timeout; the
     * unprocessed events are released when the transaction ends and picked up by the next run.
     *
     * @return whether a full batch was published, i.e. more events may be waiting
     */
    boolean publishBatch() {
        List<OutboxEvent> events = repository.claimDue(Instant.now(), properties.batchSize());
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
                log.error("Outbox event {} ({} of {} {}) failed to publish: attempt {}, unpublished for {}, next try in {}",
                        event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(),
                        event.getAttempts(), Duration.between(event.getCreatedAt(), now), retryAfter, e);
                return false;
            }
        }
        return events.size() == properties.batchSize();
    }

    // Published events are only needed for troubleshooting; keep them for the retention period
    @Scheduled(fixedDelayString = "${catalog.outbox.cleanup-interval}")
    @Transactional
    public int deletePublishedEvents() {
        int deleted = repository.deletePublishedBefore(Instant.now().minus(properties.retention()));
        if (deleted > 0) {
            log.info("Deleted {} published outbox events older than {}", deleted, properties.retention());
        }
        return deleted;
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
