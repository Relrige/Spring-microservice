package ua.edu.ukma.springers.voltstore.customer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of {@code POST /customers/cart/items}. Only the format is checked here (400); the upper limit of 10 is a cart
 * rule checked by the aggregate (422), so there is deliberately no {@code @Max}.
 */
public record AddCartItemRequest(@NotNull UUID productId, @NotNull @Min(1) Integer quantity) {
}
