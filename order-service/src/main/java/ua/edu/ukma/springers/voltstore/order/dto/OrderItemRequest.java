package ua.edu.ukma.springers.voltstore.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** One product line of a cart, as the customer sends it (EP-ORD-01/02). Prices are never accepted from the client. */
public record OrderItemRequest(
        @NotNull(message = "Product id is required")
        UUID productId,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        @Max(value = OrderItemRequest.MAX_QUANTITY, message = "Quantity must be at most " + OrderItemRequest.MAX_QUANTITY)
        Integer quantity
) {
    /**
     * The cart's own limit per line (Customer INV-CART-02), so calling the API directly cannot order more than the
     * shop allows. The total has its own limit, checked against Catalog's prices ({@code OrderPlacementService}).
     */
    public static final int MAX_QUANTITY = 10;
}
