package ua.edu.ukma.springers.voltstore.order.openapi;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Documentation only: the {@code 402} of EP-ORD-03; the order stays payable with another card. */
@Schema(name = "PaymentDeclinedProblem", description = "The card was declined (402)")
public record PaymentDeclinedProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED, description = "Payment's decline reason") String reason) {
}
