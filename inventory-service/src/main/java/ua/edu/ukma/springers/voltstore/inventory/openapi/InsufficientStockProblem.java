package ua.edu.ukma.springers.voltstore.inventory.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.inventory.dto.InsufficientItem;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Documentation only: the {@code 422} of EP-INV-06, listing every short item; nothing was reserved. */
@Schema(name = "InsufficientStockProblem", description = "Not enough stock for one or more items (422)")
public record InsufficientStockProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED, description = "Every short item; a product without stock counts as 0 available")
        List<InsufficientItem> insufficientItems) {
}
