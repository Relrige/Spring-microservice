package ua.edu.ukma.springers.voltstore.order.dto;

import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** EP-ORD-02's {@code 201} body: what the customer needs to pay before the window closes. */
public record PlacedOrderResponse(UUID orderId, OrderStatus status, BigDecimal totalAmount, String currency,
                                  Instant reservationExpiresAt) {

    public static PlacedOrderResponse of(OrderEntity order) {
        return new PlacedOrderResponse(order.getId(), order.getStatus(), order.getTotalAmount(), order.getCurrency(),
                order.getReservationExpiresAt());
    }
}
