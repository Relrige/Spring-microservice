package ua.edu.ukma.springers.voltstore.order.events;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

/**
 * An event of the order lifecycle on topic {@code order.order-events}, key = orderId (see the Event Catalogue,
 * section Order Lifecycle). The record components are the JSON payload; {@link #eventType()} travels as the
 * {@code eventType} header, not in the payload.
 */
public sealed interface OrderEvent permits OrderPlacedEvent, OrderPaidEvent, OrderShippedEvent, OrderCancelledEvent {

    UUID eventId();

    UUID orderId();

    Instant timestamp();

    @JsonIgnore
    String eventType();
}
