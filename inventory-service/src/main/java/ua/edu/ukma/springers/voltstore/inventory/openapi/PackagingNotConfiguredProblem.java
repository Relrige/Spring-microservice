package ua.edu.ukma.springers.voltstore.inventory.openapi;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Documentation only: the {@code 422} of EP-INV-04/05 when the packaging values are missing. */
@Schema(name = "PackagingNotConfiguredProblem", description = "The packaging values are not configured (422)")
public record PackagingNotConfiguredProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED, description = "Always `packaging_data_missing`")
        String reason) {
}
