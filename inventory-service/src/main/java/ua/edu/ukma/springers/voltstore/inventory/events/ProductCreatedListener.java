package ua.edu.ukma.springers.voltstore.inventory.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

/**
 * EP-INV-EVT-01: a new Catalog product gets an empty StockItem, so workers can set packaging and quantities
 * right away. Idempotent (at-least-once delivery): an existing StockItem is left untouched.
 * <p>
 * Offsets: the container's default ack mode (BATCH) commits a poll's offsets only after the listener has
 * returned for its records, i.e. after the database transaction committed; a crash before that re-delivers the
 * event, which the idempotent insert absorbs. Failures follow the error handler in {@code KafkaConfig}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCreatedListener {
    private final StockService stockService;

    @KafkaListener(topics = "${inventory.kafka.topics.product-events}", filter = "productCreatedOnly")
    public void onProductCreated(ProductCreatedEvent event) {
        if (stockService.ensureStockItem(event.productId())) {
            log.info("StockItem created for new product {} (eventId={})", event.productId(), event.eventId());
        } else {
            log.debug("StockItem for product {} already exists (eventId={})", event.productId(), event.eventId());
        }
    }
}
