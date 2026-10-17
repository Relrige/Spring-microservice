package ua.edu.ukma.springers.voltstore.order.config;

import lombok.experimental.UtilityClass;

@UtilityClass
public class KafkaTopics {
    // Every event names its type in this header (the same convention in all services); consumers dispatch on it
    public static final String EVENT_TYPE_HEADER = "eventType";

    // Events also carry their eventId as a header (as Catalog's and Inventory's do); the payload keeps it too
    public static final String EVENT_ID_HEADER = "eventId";
}
