package ua.edu.ukma.springers.voltstore.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.customer.entities.CartLine;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** One line of the cart in every cart response; a cart is a JSON array of these. */
public record CartItemResponse(@Schema(requiredMode = REQUIRED) UUID productId,
                               @Schema(requiredMode = REQUIRED) int quantity) {

    public static List<CartItemResponse> from(List<CartLine> lines) {
        return lines.stream().map(line -> new CartItemResponse(line.productId(), line.quantity())).toList();
    }
}
