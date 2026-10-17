package ua.edu.ukma.springers.voltstore.order.events;

import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The worker handed the parcel to the courier (EP-ORD-08). Inventory consumes the reservation, Delivery hands the
 * parcel to the carrier, Notification sends the tracking email.
 */
public record OrderShippedEvent(UUID eventId, UUID orderId, String ttn, String customerEmail, Instant timestamp)
        implements OrderEvent {

    public static OrderShippedEvent of(OrderEntity order, String ttn, Instant now) {
        return new OrderShippedEvent(UUID.randomUUID(), order.getId(), Objects.requireNonNull(ttn),
                order.getCustomerEmail(), now);
    }

    @Override
    public String eventType() {
        return "OrderShipped";
    }
}
