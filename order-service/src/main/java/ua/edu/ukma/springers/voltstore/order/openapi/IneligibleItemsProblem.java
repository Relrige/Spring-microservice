package ua.edu.ukma.springers.voltstore.order.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Documentation only: the {@code 422} of EP-ORD-01/02 listing every product that is not active or not in stock. */
@Schema(name = "IneligibleItemsProblem", description = "Some products cannot be ordered (422)")
public record IneligibleItemsProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED) List<IneligibleItem> ineligibleItems) {
}
