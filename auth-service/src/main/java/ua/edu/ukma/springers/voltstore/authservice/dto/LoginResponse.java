package ua.edu.ukma.springers.voltstore.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record LoginResponse(
        @Schema(requiredMode = REQUIRED)
        String token
) {
}
