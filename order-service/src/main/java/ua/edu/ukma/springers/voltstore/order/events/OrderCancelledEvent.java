package ua.edu.ukma.springers.voltstore.order.events;

import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * The order ended without delivery: cancelled by the customer (EP-ORD-06) or expired unpaid (EP-ORD-JOB-01).
 * Notification sends a different email per reason.
 */
public record OrderCancelledEvent(UUID eventId, UUID orderId, UUID customerId, String customerEmail, Reason reason,
                                  Instant timestamp) implements OrderEvent {

    public enum Reason {
        CUSTOMER_CANCELLED,
        EXPIRED
    }

    public static OrderCancelledEvent of(OrderEntity order, Reason reason, Instant now) {
        return new OrderCancelledEvent(UUID.randomUUID(), order.getId(), order.getCustomerId(),
                order.getCustomerEmail(), reason, now);
    }

    @Override
    public String eventType() {
        return "OrderCancelled";
    }
}
