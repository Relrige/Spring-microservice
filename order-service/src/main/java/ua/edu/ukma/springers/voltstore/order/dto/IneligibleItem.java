package ua.edu.ukma.springers.voltstore.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A product that cannot be ordered (EP-ORD-01/02): {@code reason} is {@code NOT_ACTIVE} or {@code INSUFFICIENT_STOCK}. */
public record IneligibleItem(@Schema(requiredMode = REQUIRED) UUID productId,
                             @Schema(requiredMode = REQUIRED) Reason reason) {

    public enum Reason {
        NOT_ACTIVE,
        INSUFFICIENT_STOCK
    }
}
