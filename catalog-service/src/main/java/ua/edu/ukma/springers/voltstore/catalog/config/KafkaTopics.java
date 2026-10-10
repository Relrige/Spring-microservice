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

    private KafkaTopics() {
    }
}
