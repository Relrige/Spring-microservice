package ua.edu.ukma.springers.voltstore.customer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ua.edu.ukma.springers.voltstore.customer.entities.CartLine;

import java.util.List;
import java.util.UUID;

/**
 * Body of the batch requests {@code PUT /customers/cart} and {@code POST /customers/cart/merge}. {@code items} may be
 * empty. Only the format is checked here (400); duplicates and the limits are cart rules (422), checked by the cart.
 */
public record CartItemsRequest(@NotNull List<@NotNull @Valid Item> items) {

    public record Item(@NotNull UUID productId, @NotNull @Min(1) Integer quantity) {
    }

    public List<CartLine> toLines() {
        return items.stream().map(item -> new CartLine(item.productId(), item.quantity())).toList();
    }
}
