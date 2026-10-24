package ua.edu.ukma.springers.voltstore.order.openapi;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Documentation only: the {@code 422} of EP-ORD-02 when the total exceeds the payment limit. */
@Schema(name = "OrderTotalTooLargeProblem", description = "The order total is above what can be paid at once (422)")
public record OrderTotalTooLargeProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED, description = "The largest total that can be paid") BigDecimal maxTotal) {
}
