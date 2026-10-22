package ua.edu.ukma.springers.voltstore.inventory.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService.ProductCreatedResult;

import java.nio.charset.StandardCharsets;

/**
 * Catalog's product events, in <b>one listener container</b>, dispatched by the {@code eventType} header:
 * <ul>
 *   <li>{@code ProductCreated} (EP-INV-EVT-01): a new Catalog product gets a StockItem, so workers can set
 *   packaging and quantities right away, together with the copy of its product data (D8);</li>
 *   <li>{@code ProductUpdated} (EP-INV-EVT-03): the copy is updated if the event is newer.</li>
 * </ul>
 * Two containers in the same consumer group on one topic would split its partitions between them, and each would
 * drop the other's type, losing events. Other types are discarded by the {@code productEventsOnly} filter.
 * <p>
 * <b>Retries (non-blocking):</b> a failed record is moved to a retry topic and the partition goes on with the
 * next records. Attempt 1 on {@code catalog.product-events}, attempts 2–4 on
 * {@code catalog.product-events-inventory-retry-0/-1/-2} (after 1 s, 2 s, 4 s), then
 * {@code catalog.product-events-inventory-dlt}. Records that can never succeed (payload not convertible, e.g. not
 * JSON or without {@code eventId}/{@code productId}, or a {@code ProductUpdated} without its required fields) go to
 * the DLT at once. Retrying out of order is harmless: a product has one ProductCreated, and the version check of the
 * copy makes an older state that arrives late change nothing.
 * <p>
 * <b>Duplicates:</b> at-least-once delivery (Catalog's outbox, Kafka redelivery, retries) is absorbed by the
 * Idempotent Consumer in {@link StockService#applyProductCreated} and by the version check for
 * {@code ProductUpdated}. Offsets are committed after the listener returned, i.e. after the database transaction
 * committed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventsListener {
    private final StockService stockService;

    @Value("${inventory.kafka.topics.product-events}")
    private String sourceTopic;

    // EXPERIMENT ONLY (default false): fail every first delivery after the database commit, like a consumer that
    // crashes before committing its offset. The record is redelivered through the retry topic, and the
    // Idempotent Consumer must skip it. Used by the fault-tolerance experiment, never enabled in deployments.
    @Value("${inventory.experiments.fail-after-commit:false}")
    private boolean failAfterCommit;

    @RetryableTopic(
            attempts = "${inventory.kafka.product-events.attempts}",
            backOff = @BackOff(delayString = "${inventory.kafka.product-events.backoff-delay-ms}",
                    multiplier = 2, maxDelay = 10_000),
            // Never retried: straight to the DLT, like an unconvertible payload
            exclude = InvalidProductEventException.class,
            retryTopicSuffix = "-inventory-retry",
            dltTopicSuffix = "-inventory-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            // Created by Inventory's admin client (broker auto-creation is off); a single broker → replication 1
            autoCreateTopics = "true",
            numPartitions = "3",
            replicationFactor = "1")
    @KafkaListener(topics = "${inventory.kafka.topics.product-events}", filter = "productEventsOnly")
    public void onProductEvent(ProductEvent event, @Header(KafkaTopics.EVENT_TYPE_HEADER) String eventType,
                               @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        switch (eventType) {
            case ProductEvent.CREATED -> onProductCreated(event, topic);
            case ProductEvent.UPDATED -> onProductUpdated(event, topic);
            // The filter lets only these two through
            default -> throw new IllegalStateException("Unexpected product event type " + eventType);
        }

        if (failAfterCommit && topic.equals(sourceTopic)) {
            log.warn("EXPERIMENT: simulated consumer failure after commit, eventId={}; the record will be redelivered",
                    event.eventId());
            throw new IllegalStateException("EXPERIMENT: simulated consumer failure after commit, eventId="
                    + event.eventId());
        }
    }

    private void onProductCreated(ProductEvent event, String topic) {
        ProductCreatedResult result = stockService.applyProductCreated(event);
        switch (result) {
            case APPLIED -> log.info("ProductCreated applied for product {} (eventId={}, version={}, topic={})",
                    event.productId(), event.eventId(), event.version(), topic);
            case UNCHANGED -> log.info("ProductCreated for product {} changed nothing: the StockItem existed with "
                    + "the same or a newer copy (eventId={})", event.productId(), event.eventId());
            case DUPLICATE_EVENT -> log.info("Duplicate ProductCreated eventId={} for product {} skipped (topic={})",
                    event.eventId(), event.productId(), topic);
        }
    }

    private void onProductUpdated(ProductEvent event, String topic) {
        if (event.title() == null || event.status() == null || event.version() == null) {
            throw new InvalidProductEventException("ProductUpdated eventId=" + event.eventId()
                    + " lacks title, status or version");
        }
        boolean applied = stockService.applyProductUpdated(event);
        if (applied) {
            log.info("ProductUpdated applied for product {} (eventId={}, version={}, topic={})",
                    event.productId(), event.eventId(), event.version(), topic);
        } else {
            log.info("ProductUpdated for product {} changed nothing: the copy is as new or newer (eventId={}, "
                    + "version={})", event.productId(), event.eventId(), event.version());
        }
    }

    // Final stop for records that could not be processed: logged for an operator, who can fix the cause and
    // re-publish the record to the source topic (its eventId or version still protects against double application)
    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("Product event dead-lettered: type={} key={} topic={} partition={} offset={} cause={}: {}",
                headerText(record, KafkaTopics.EVENT_TYPE_HEADER), record.key(), record.topic(), record.partition(),
                record.offset(), headerText(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN),
                headerText(record, KafkaHeaders.EXCEPTION_MESSAGE));
    }

    private static String headerText(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
