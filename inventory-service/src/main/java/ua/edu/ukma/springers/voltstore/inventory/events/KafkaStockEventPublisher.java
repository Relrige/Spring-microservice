package ua.edu.ukma.springers.voltstore.inventory.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sends stock events with a plain {@link KafkaTemplate} after the surrounding transaction commits, so
 * a rolled-back change emits nothing. Known weakness (dual write): a crash between the commit and the
 * send, or a failed send, loses the event. The transactional outbox (task 17) replaces this class.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaStockEventPublisher implements StockEventPublisher {
    public static final String EVENT_TYPE_HEADER = KafkaTopics.EVENT_TYPE_HEADER;

    private final ApplicationEventPublisher applicationEventPublisher;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void publish(StockEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    /**
     * Without an active transaction the event is sent immediately (fallbackExecution).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void send(StockEvent event) {
        try {
            // Not waiting for the acknowledgment: a slow or failing broker must not fail the request.
            kafkaTemplate.send(toRecord(event)).whenComplete((result, error) -> {
                if (error != null) {
                    log.error("Failed to publish {} {} for product {}",
                            event.eventType(), event.eventId(), event.productId(), error);
                }
            });
        } catch (RuntimeException e) {
            // e.g. metadata could not be fetched within max.block.ms because the broker is down
            log.error("Failed to publish {} {} for product {}",
                    event.eventType(), event.eventId(), event.productId(), e);
        }
    }

    private ProducerRecord<String, Object> toRecord(StockEvent event) {
        RecordHeader typeHeader = new RecordHeader(EVENT_TYPE_HEADER, event.eventType().getBytes(StandardCharsets.UTF_8));
        return new ProducerRecord<>(KafkaTopics.STOCK_EVENTS,
                null,
                event.productId().toString(),
                event,
                List.of(typeHeader));
    }
}
