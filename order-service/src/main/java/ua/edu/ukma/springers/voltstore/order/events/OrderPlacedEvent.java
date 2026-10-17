package ua.edu.ukma.springers.voltstore.order.events;

import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A new order waits for payment (EP-ORD-02). Consumed by Notification ("Your order has been placed"). */
public record OrderPlacedEvent(UUID eventId, UUID orderId, UUID customerId, String customerEmail,
                               BigDecimal totalAmount, String currency, Instant reservationExpiresAt,
                               Instant timestamp) implements OrderEvent {

    public static OrderPlacedEvent of(OrderEntity order, Instant now) {
        return new OrderPlacedEvent(UUID.randomUUID(), order.getId(), order.getCustomerId(), order.getCustomerEmail(),
                order.getTotalAmount(), order.getCurrency(), order.getReservationExpiresAt(), now);
    }

    @Override
    public String eventType() {
        return "OrderPlaced";
    }
}
