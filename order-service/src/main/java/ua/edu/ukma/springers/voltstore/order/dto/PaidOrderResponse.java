package ua.edu.ukma.springers.voltstore.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** EP-ORD-03's {@code 200} body. */
public record PaidOrderResponse(@Schema(requiredMode = REQUIRED) UUID orderId,
                                @Schema(requiredMode = REQUIRED) OrderStatus status) {
}
