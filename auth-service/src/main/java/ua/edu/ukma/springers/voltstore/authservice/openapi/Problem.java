package ua.edu.ukma.springers.voltstore.authservice.openapi;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Documentation only: the RFC 9457 problem every error answers (rendered by {@code GlobalExceptionHandler} and
 * {@code SecurityErrorHandlers}). Fields marked REQUIRED are always present; the rest may be missing (conventions D5).
 */
@Schema(name = "Problem", description = "RFC 9457 problem details")
public record Problem(
        @Schema(requiredMode = REQUIRED, description = "Error kind: `https://voltstore.com/errors/<kind>`")
        URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        @Schema(description = "Name of the service that answered") String service) {
}
