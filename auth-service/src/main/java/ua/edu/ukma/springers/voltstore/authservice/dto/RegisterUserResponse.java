package ua.edu.ukma.springers.voltstore.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record RegisterUserResponse(
        @Schema(requiredMode = REQUIRED)
        UUID userId
) {
}
