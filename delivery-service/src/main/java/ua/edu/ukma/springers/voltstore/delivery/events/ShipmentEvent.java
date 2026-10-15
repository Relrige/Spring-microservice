package ua.edu.ukma.springers.voltstore.delivery.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An event about a shipment, for Order Service (and Notification). All shipment events share the topic
 * {@code delivery.shipment-events} and are keyed by {@code orderId}, so the events of one shipment keep their order.
 * The payload is {@code { eventId, orderId, ttn, timestamp }}; the type travels in the {@code eventType} header.
 */
public sealed interface ShipmentEvent permits TtnGeneratedEvent, OrderDeliveredEvent {
    UUID eventId();

    UUID orderId();

    String ttn();

    Instant timestamp();

    /** Sent as the {@code eventType} Kafka header, not in the payload. */
    String eventType();
}
