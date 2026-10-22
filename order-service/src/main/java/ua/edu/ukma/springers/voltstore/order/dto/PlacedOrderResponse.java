package ua.edu.ukma.springers.voltstore.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** EP-ORD-02's {@code 201} body: what the customer needs to pay before the window closes. */
public record PlacedOrderResponse(@Schema(requiredMode = REQUIRED) UUID orderId,
                                  @Schema(requiredMode = REQUIRED) OrderStatus status,
                                  @Schema(requiredMode = REQUIRED) BigDecimal totalAmount,
                                  @Schema(requiredMode = REQUIRED) String currency,
                                  @Schema(requiredMode = REQUIRED) Instant reservationExpiresAt) {

    public static PlacedOrderResponse of(OrderEntity order) {
        return new PlacedOrderResponse(order.getId(), order.getStatus(), order.getTotalAmount(), order.getCurrency(),
                order.getReservationExpiresAt());
    }
}
