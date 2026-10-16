package ua.edu.ukma.springers.voltstore.customer.config;

import lombok.experimental.UtilityClass;

@UtilityClass
public class KafkaTopics {
    // Every event names its type in this header (the same convention in all services)
    public static final String EVENT_TYPE_HEADER = "eventType";

    /**
     * Customer's dead-letter topic for a consumed source topic, e.g. {@code order.order-events} →
     * {@code customer.order.order-events.dlt}. Prefixed with the consumer, because the consumer owns its DLT and
     * other consumer groups of the same source topic (Delivery, Inventory) have their own.
     */
    public static String deadLetterTopicOf(String sourceTopic) {
        return "customer." + sourceTopic + ".dlt";
    }
}
