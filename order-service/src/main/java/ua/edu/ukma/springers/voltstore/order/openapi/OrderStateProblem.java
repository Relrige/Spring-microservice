package ua.edu.ukma.springers.voltstore.order.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.net.URI;
import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Documentation only: a {@code 409} from {@code InvalidOrderStateException} (and its subclass for a cancellation in progress). */
@Schema(name = "OrderStateProblem", description = "The order's status does not allow this (409)")
public record OrderStateProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED, description = "The order's current status") OrderStatus orderStatus) {
}
