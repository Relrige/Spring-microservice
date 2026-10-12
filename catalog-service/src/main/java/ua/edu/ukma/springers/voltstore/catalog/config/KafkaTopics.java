package ua.edu.ukma.springers.voltstore.catalog.config;

/**
 * Names of the Kafka topics used by Catalog Service.
 * <p>
 * Naming convention: {@code <owning-service>.<aggregate>-events}. One topic per aggregate (not per event
 * type), because Kafka orders messages only within a partition: all events of one product, keyed by
 * productId, must share a topic to be consumed in order. The producing service owns and declares its topics.
 */
public final class KafkaTopics {
    // Produced by Catalog: ProductCreated (key = productId)
    public static final String PRODUCT_EVENTS = "catalog.product-events";

    // Produced by Inventory: ProductStockReplenished and ProductStockDepleted (key = productId). Both event
    // types share this one topic, so a later Depleted can never overtake an earlier Replenished.
    public static final String STOCK_EVENTS = "inventory.stock-events";

    // Owned by Catalog: stock events that could not be processed (poison messages, retries exhausted)
    public static final String STOCK_EVENTS_DLT = "catalog.stock-events-dlt";

    // Message headers set by producers on every event
    public static final String EVENT_TYPE_HEADER = "eventType";
    public static final String EVENT_ID_HEADER = "eventId";

    private KafkaTopics() {
    }
}
