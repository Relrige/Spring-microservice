package ua.edu.ukma.springers.voltstore.inventory.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

/**
 * EP-INV-EVT-01: creates an empty StockItem for every product created in Catalog.
 * <p>
 * Delivery is at-least-once, so the handler is idempotent: an existing StockItem (from a duplicate event or
 * from an earlier on-demand creation) is left unchanged. The offset is committed only after this method
 * returns, i.e. after the database transaction has committed. Failures are handled by the error handler in
 * {@code KafkaConfig}. A missing topic is not fatal: the container keeps polling until Catalog creates it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCreatedListener {
    private final StockService stockService;

    @KafkaListener(topics = "${inventory.kafka.topics.product-events}", filter = ProductCreatedFilter.BEAN_NAME)
    public void onProductCreated(ProductCreatedEvent event) {
        boolean created = stockService.ensureStockItem(event.productId());
        if (created) {
            log.info("StockItem created for product {} (eventId={})", event.productId(), event.eventId());
        } else {
            log.debug("StockItem for product {} already exists (eventId={})", event.productId(), event.eventId());
        }
    }
}
