package ua.edu.ukma.springers.voltstore.inventory.config;

import lombok.experimental.UtilityClass;

@UtilityClass
public class KafkaTopics {
    // Owned by Inventory: ProductStockReplenished, ProductStockDepleted (key = productId)
    public static final String STOCK_EVENTS = "inventory.stock-events";

    // Every event names its type in this header (the same convention in all services)
    public static final String EVENT_TYPE_HEADER = "eventType";

    /**
     * Inventory's dead-letter topic for a consumed source topic, e.g. {@code catalog.product-events} →
     * {@code inventory.catalog.product-events.dlt}. Prefixed with the consumer, because the consumer owns its DLT
     * and other consumer groups of the same source topic have their own.
     */
    public static String deadLetterTopicOf(String sourceTopic) {
        return "inventory." + sourceTopic + ".dlt";
    }
}
