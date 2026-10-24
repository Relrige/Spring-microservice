package ua.edu.ukma.springers.voltstore.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * EP-ORD-06's {@code 202} body: {@code CANCELLED} when the saga finished, {@code CANCELLATION_REQUESTED} when a step
 * is still pending and will be retried.
 */
public record CancellationResponse(@Schema(requiredMode = REQUIRED) UUID orderId,
                                   @Schema(requiredMode = REQUIRED) OrderStatus status) {
}
