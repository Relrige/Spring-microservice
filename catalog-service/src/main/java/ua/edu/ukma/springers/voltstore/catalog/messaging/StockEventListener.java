package ua.edu.ukma.springers.voltstore.catalog.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.events.StockEvent;
import ua.edu.ukma.springers.voltstore.catalog.services.ProductService;

import java.nio.charset.StandardCharsets;

/**
 * EP-CAT-EVT-01 / EP-CAT-EVT-02: keeps the {@code Product.stockStatus} read-model in sync with Inventory.
 * <p>
 * Offsets: the container's default ack mode (BATCH) commits the offsets of a poll only after the listener
 * has returned for its records, i.e. after the database update committed. A crash before that re-delivers
 * the records, which is harmless because applying a stock status is idempotent (it sets a value, it does
 * not toggle or count). Re-delivery replays a partition in order, so an older event never overwrites a newer
 * one. Failures are handled by the error handler in {@code KafkaConfig} (bounded retries, then the DLT).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockEventListener {
    public static final String GROUP_ID = "catalog-service";

    private final ProductService productService;

    @KafkaListener(
            topics = KafkaTopics.STOCK_EVENTS,
            groupId = GROUP_ID,
            properties = "spring.json.value.default.type=ua.edu.ukma.springers.voltstore.catalog.events.StockEvent")
    public void onStockEvent(ConsumerRecord<String, StockEvent> record) {
        String eventType = header(record, KafkaTopics.EVENT_TYPE_HEADER);
        StockStatus stockStatus = switch (eventType) {
            case StockEvent.REPLENISHED -> StockStatus.IN_STOCK;
            case StockEvent.DEPLETED -> StockStatus.OUT_OF_STOCK;
            case null, default -> null;
        };
        // Other event types Inventory may add to its topic later are not Catalog's business
        if (stockStatus == null || record.value() == null) {
            log.warn("Ignoring stock event with eventType={} key={} partition={} offset={}",
                    eventType, record.key(), record.partition(), record.offset());
            return;
        }

        StockEvent event = record.value();
        if (!productService.updateStockStatus(event.productId(), stockStatus)) {
            // Acknowledged anyway: retrying cannot make an unknown product appear (spec: log and acknowledge)
            log.warn("Stock event {} for unknown productId={} ignored", eventType, event.productId());
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
