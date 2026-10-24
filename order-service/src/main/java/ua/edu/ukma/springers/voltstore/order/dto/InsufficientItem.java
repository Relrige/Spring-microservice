package ua.edu.ukma.springers.voltstore.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Inventory's answer for a product it could not reserve, passed to the customer unchanged (EP-ORD-02). */
public record InsufficientItem(@Schema(requiredMode = REQUIRED) UUID productId,
                               @Schema(requiredMode = REQUIRED) int requested,
                               @Schema(requiredMode = REQUIRED) int available) {
}
