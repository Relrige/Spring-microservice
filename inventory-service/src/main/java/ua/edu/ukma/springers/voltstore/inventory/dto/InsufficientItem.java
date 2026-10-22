package ua.edu.ukma.springers.voltstore.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record InsufficientItem(@Schema(requiredMode = REQUIRED) UUID productId,
                               @Schema(requiredMode = REQUIRED) int requested,
                               @Schema(requiredMode = REQUIRED) int available) {
}
