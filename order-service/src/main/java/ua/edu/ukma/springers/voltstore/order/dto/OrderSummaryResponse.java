package ua.edu.ukma.springers.voltstore.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** One line of the order history (EP-ORD-04). */
public record OrderSummaryResponse(@Schema(requiredMode = REQUIRED) UUID orderId,
                                   @Schema(requiredMode = REQUIRED) OrderStatus status,
                                   @Schema(requiredMode = REQUIRED) BigDecimal totalAmount,
                                   @Schema(requiredMode = REQUIRED) String currency,
                                   @Schema(requiredMode = REQUIRED) Instant placedAt) {

    public static OrderSummaryResponse of(OrderEntity order) {
        return new OrderSummaryResponse(order.getId(), order.getStatus(), order.getTotalAmount(), order.getCurrency(),
                order.getPlacedAt());
    }
}
