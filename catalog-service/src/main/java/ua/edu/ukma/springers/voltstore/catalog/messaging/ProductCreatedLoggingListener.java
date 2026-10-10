package ua.edu.ukma.springers.voltstore.catalog.messaging;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.events.ProductCreatedEvent;

/**
 * TEMPORARY demonstration consumer (task 11): proves the broker path end to end by logging every
 * ProductCreated event with its key, partition and offset. Remove it in task 18; the real consumer of
 * ProductCreated belongs to Inventory Service.
 */
@Slf4j
@Component
public class ProductCreatedLoggingListener {
    public static final String GROUP_ID = "catalog-service.product-created-logger";

    @KafkaListener(
            topics = KafkaTopics.PRODUCT_EVENTS,
            groupId = GROUP_ID,
            properties = "spring.json.value.default.type=ua.edu.ukma.springers.voltstore.catalog.events.ProductCreatedEvent")
    public void onProductCreated(ConsumerRecord<String, ProductCreatedEvent> record) {
        ProductCreatedEvent event = record.value();
        log.info("Received ProductCreated: key={}, partition={}, offset={}, eventId={}, productId={}",
                record.key(), record.partition(), record.offset(), event.eventId(), event.productId());
    }
}
