package ua.edu.ukma.springers.voltstore.order.dto;

import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.util.UUID;

/**
 * EP-ORD-06's {@code 202} body: {@code CANCELLED} when the saga finished, {@code CANCELLATION_REQUESTED} when a step
 * is still pending and will be retried.
 */
public record CancellationResponse(UUID orderId, OrderStatus status) {
}
