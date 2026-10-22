package ua.edu.ukma.springers.voltstore.delivery.openapi;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Documentation only: the {@code 424} of EP-DEL-03 when Inventory or the carrier rejected the manual TTN attempt. */
@Schema(name = "TtnGenerationFailedProblem", description = "The TTN could not be generated (424)")
public record TtnGenerationFailedProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED, description = "Why the attempt failed, e.g. `packaging_data_missing:{productId}`")
        String reason) {
}
