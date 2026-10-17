package ua.edu.ukma.springers.voltstore.order.dto;

import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.util.UUID;

/** EP-ORD-08's {@code 200} body. */
public record ShippedOrderResponse(UUID orderId, OrderStatus status, String ttn) {
}
