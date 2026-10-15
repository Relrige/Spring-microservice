package ua.edu.ukma.springers.voltstore.delivery.config;

import lombok.experimental.UtilityClass;

@UtilityClass
public class KafkaTopics {
    // Owned by Delivery: TTNGenerated, OrderDelivered (key = orderId)
    public static final String SHIPMENT_EVENTS = "delivery.shipment-events";

    // Every event names its type in this header (the same convention in all services)
    public static final String EVENT_TYPE_HEADER = "eventType";

    // Shipment events also carry their eventId as a header (as Catalog's and Inventory's events do)
    public static final String EVENT_ID_HEADER = "eventId";

    /**
     * Delivery's dead-letter topic for a consumed source topic, e.g. {@code order.order-events} →
     * {@code delivery.order.order-events.dlt}. Prefixed with the consumer, because the consumer owns its DLT and
     * other consumer groups of the same source topic have their own.
     */
    public static String deadLetterTopicOf(String sourceTopic) {
        return "delivery." + sourceTopic + ".dlt";
    }
}
