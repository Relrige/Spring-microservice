package ua.edu.ukma.springers.voltstore.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record CreateProductResponse(
        @Schema(requiredMode = REQUIRED)
        UUID productId
) {
}
