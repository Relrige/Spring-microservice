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
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService.ProductCreatedResult;

import java.nio.charset.StandardCharsets;

/**
 * EP-INV-EVT-01: a new Catalog product gets an empty StockItem, so workers can set packaging and quantities
 * right away.
 * <p>
 * <b>Retries (non-blocking):</b> a failed record is moved to a retry topic and the partition goes on with the
 * next records. Attempt 1 on {@code catalog.product-events}, attempts 2–4 on
 * {@code catalog.product-events-inventory-retry-0/-1/-2} (after 1 s, 2 s, 4 s), then
 * {@code catalog.product-events-inventory-dlt}. Records that can never succeed (payload not convertible, e.g. not
 * JSON or without {@code eventId}/{@code productId}) go to the DLT at once. Ordering between products does not
 * matter here, and a product has one ProductCreated, so retrying out of order is harmless.
 * <p>
 * <b>Duplicates:</b> at-least-once delivery (Catalog's outbox, Kafka redelivery, retries) is absorbed by the
 * Idempotent Consumer in {@link StockService#applyProductCreated}. Offsets are committed after the listener
 * returned, i.e. after the database transaction committed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCreatedListener {
    private final StockService stockService;

    @Value("${inventory.kafka.topics.product-events}")
    private String sourceTopic;

    // EXPERIMENT ONLY (default false): fail every first delivery after the database commit, like a consumer that
    // crashes before committing its offset. The record is redelivered through the retry topic, and the
    // Idempotent Consumer must skip it. Used by the fault-tolerance experiment, never enabled in deployments.
    @Value("${inventory.experiments.fail-after-commit:false}")
    private boolean failAfterCommit;

    @RetryableTopic(
            attempts = "${inventory.kafka.product-created.attempts}",
            backOff = @BackOff(delayString = "${inventory.kafka.product-created.backoff-delay-ms}",
                    multiplier = 2, maxDelay = 10_000),
            retryTopicSuffix = "-inventory-retry",
            dltTopicSuffix = "-inventory-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            // Created by Inventory's admin client (broker auto-creation is off); a single broker → replication 1
            autoCreateTopics = "true",
            numPartitions = "3",
            replicationFactor = "1")
    @KafkaListener(topics = "${inventory.kafka.topics.product-events}", filter = "productCreatedOnly")
    public void onProductCreated(ProductCreatedEvent event, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        ProductCreatedResult result = stockService.applyProductCreated(event.eventId(), event.productId());
        switch (result) {
            case CREATED -> log.info("StockItem created for new product {} (eventId={}, topic={})",
                    event.productId(), event.eventId(), topic);
            case ALREADY_EXISTED -> log.info("StockItem for product {} already existed (eventId={})",
                    event.productId(), event.eventId());
            case DUPLICATE_EVENT -> log.info("Duplicate ProductCreated eventId={} for product {} skipped (topic={})",
                    event.eventId(), event.productId(), topic);
        }

        if (failAfterCommit && topic.equals(sourceTopic)) {
            log.warn("EXPERIMENT: simulated consumer failure after commit, eventId={}; the record will be redelivered",
                    event.eventId());
            throw new IllegalStateException("EXPERIMENT: simulated consumer failure after commit, eventId="
                    + event.eventId());
        }
    }

    // Final stop for records that could not be processed: logged for an operator, who can fix the cause and
    // re-publish the record to the source topic (its eventId still protects against double application)
    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("ProductCreated dead-lettered: key={} topic={} partition={} offset={} cause={}: {}",
                record.key(), record.topic(), record.partition(), record.offset(),
                headerText(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN), headerText(record, KafkaHeaders.EXCEPTION_MESSAGE));
    }

    private static String headerText(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
