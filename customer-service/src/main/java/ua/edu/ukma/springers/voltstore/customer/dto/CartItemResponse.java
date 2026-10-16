package ua.edu.ukma.springers.voltstore.customer.dto;

import ua.edu.ukma.springers.voltstore.customer.entities.CartLine;

import java.util.List;
import java.util.UUID;

/** One line of the cart in every cart response; a cart is a JSON array of these. */
public record CartItemResponse(UUID productId, int quantity) {

    public static List<CartItemResponse> from(List<CartLine> lines) {
        return lines.stream().map(line -> new CartItemResponse(line.productId(), line.quantity())).toList();
    }
}
