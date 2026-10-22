package ua.edu.ukma.springers.voltstore.catalog.openapi;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Documentation only: a {@code 400}. {@code errors} is present for field errors and absent for a malformed request
 * (invalid JSON, a non-UUID path variable). Records cannot extend each other, so the {@link Problem} fields are repeated.
 */
@Schema(name = "ValidationProblem", description = "A malformed request or broken field rules (400)")
public record ValidationProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(description = "Field or parameter name to its messages")
        Map<String, List<String>> errors) {
}
