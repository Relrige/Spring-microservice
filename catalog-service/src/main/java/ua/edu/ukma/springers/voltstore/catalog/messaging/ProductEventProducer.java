package ua.edu.ukma.springers.voltstore.catalog.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.events.ProductCreatedEvent;

import java.nio.charset.StandardCharsets;

/**
 * Publishes product events to Kafka once the database transaction that produced them has committed.
 * <p>
 * Known weakness (dual write): a crash between the commit and the send, or a broker outage, loses the
 * event; failures are only logged. The transactional outbox (task 19) fixes this.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventProducer {
    // Logical event name, so consumers can tell event types apart without Java class names in headers
    public static final String EVENT_TYPE_HEADER = "eventType";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // AFTER_COMMIT: a rolled-back create never emits an event, and consumers never see a product that is
    // not in the database yet. @Async: the HTTP request does not wait for the broker.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductCreated(ProductCreatedEvent event) {
        String key = event.productId().toString();
        ProducerRecord<String, Object> record = new ProducerRecord<>(KafkaTopics.PRODUCT_EVENTS, key, event);
        record.headers().add(EVENT_TYPE_HEADER, ProductCreatedEvent.EVENT_TYPE.getBytes(StandardCharsets.UTF_8));
        try {
            kafkaTemplate.send(record).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish ProductCreated eventId={} productId={}", event.eventId(), key, ex);
                } else {
                    log.debug("Published ProductCreated eventId={} productId={} partition={} offset={}",
                            event.eventId(), key, result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        } catch (RuntimeException ex) {
            log.error("Failed to publish ProductCreated eventId={} productId={}", event.eventId(), key, ex);
        }
    }
}
