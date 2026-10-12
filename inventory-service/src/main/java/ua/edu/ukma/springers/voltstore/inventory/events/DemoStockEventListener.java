package ua.edu.ukma.springers.voltstore.inventory.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;

import java.nio.charset.StandardCharsets;

/**
 * TEMPORARY demonstration consumer: proves the broker path end to end by reading the events this
 * service publishes. Remove it in task 15, when the first real consumer exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoStockEventListener {
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = KafkaTopics.STOCK_EVENTS, groupId = "inventory-stock-events-demo")
    public void onStockEvent(ConsumerRecord<String, String> record) {
        Header typeHeader = record.headers().lastHeader(KafkaStockEventPublisher.EVENT_TYPE_HEADER);
        if (typeHeader == null) {
            log.warn("Stock event without eventType header skipped: key={} partition={} offset={}",
                    record.key(), record.partition(), record.offset());
            return;
        }
        String eventType = new String(typeHeader.value(), StandardCharsets.UTF_8);

        StockEvent event;
        try {
            event = jsonMapper.readValue(record.value(), StockEvents.classOf(eventType));
        } catch (RuntimeException e) {
            // A demo consumer must not retry a message it can never read
            log.warn("Unreadable stock event skipped: type={} key={} partition={} offset={} payload={}",
                    eventType, record.key(), record.partition(), record.offset(), record.value(), e);
            return;
        }

        log.info("Stock event received: class={} eventId={} productId={} key={} partition={} offset={}",
                event.getClass().getSimpleName(), event.eventId(), event.productId(),
                record.key(), record.partition(), record.offset());
    }
}
