package ua.edu.ukma.springers.voltstore.order.dto;

import java.util.UUID;

/** A product that cannot be ordered (EP-ORD-01/02): {@code reason} is {@code NOT_ACTIVE} or {@code INSUFFICIENT_STOCK}. */
public record IneligibleItem(UUID productId, Reason reason) {

    public enum Reason {
        NOT_ACTIVE,
        INSUFFICIENT_STOCK
    }
}
