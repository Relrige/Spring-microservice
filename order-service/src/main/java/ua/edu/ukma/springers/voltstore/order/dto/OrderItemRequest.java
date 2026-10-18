package ua.edu.ukma.springers.voltstore.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** One product line of a cart, as the customer sends it (EP-ORD-01/02). Prices are never accepted from the client. */
public record OrderItemRequest(
        @NotNull(message = "Product id is required")
        UUID productId,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity
) {
}
