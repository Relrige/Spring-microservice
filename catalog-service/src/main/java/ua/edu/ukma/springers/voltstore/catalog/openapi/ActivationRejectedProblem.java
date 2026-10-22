package ua.edu.ukma.springers.voltstore.catalog.openapi;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Documentation only: the {@code 422} of EP-CAT-03 when Inventory says the product is not ready for activation. */
@Schema(name = "ActivationRejectedProblem", description = "The product cannot be activated (422)")
public record ActivationRejectedProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED,
                description = "Inventory's code, e.g. `packaging_data_missing` or `stock_item_not_found`")
        String reason) {
}
