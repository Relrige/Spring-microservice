package ua.edu.ukma.springers.voltstore.order.dto;

import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One line of the order history (EP-ORD-04). */
public record OrderSummaryResponse(UUID orderId, OrderStatus status, BigDecimal totalAmount, String currency,
                                   Instant placedAt) {

    public static OrderSummaryResponse of(OrderEntity order) {
        return new OrderSummaryResponse(order.getId(), order.getStatus(), order.getTotalAmount(), order.getCurrency(),
                order.getPlacedAt());
    }
}
