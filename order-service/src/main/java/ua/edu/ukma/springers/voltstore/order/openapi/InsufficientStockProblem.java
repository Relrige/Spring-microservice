package ua.edu.ukma.springers.voltstore.order.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Documentation only: the {@code 422} of EP-ORD-02 when Inventory refuses the reservation; Inventory's items unchanged. */
@Schema(name = "InsufficientStockProblem", description = "Inventory cannot reserve enough stock (422)")
public record InsufficientStockProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED) List<InsufficientItem> insufficientItems) {
}
