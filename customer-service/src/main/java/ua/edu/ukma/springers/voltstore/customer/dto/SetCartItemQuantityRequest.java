package ua.edu.ukma.springers.voltstore.customer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PATCH /customers/cart/items/{productId}}; no {@code @Max}, see {@link AddCartItemRequest}. */
public record SetCartItemQuantityRequest(@NotNull @Min(1) Integer quantity) {
}
