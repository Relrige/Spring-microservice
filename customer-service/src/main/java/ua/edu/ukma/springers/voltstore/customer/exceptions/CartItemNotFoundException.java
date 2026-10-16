package ua.edu.ukma.springers.voltstore.customer.exceptions;

import lombok.Getter;

import java.util.UUID;

@Getter
public class CartItemNotFoundException extends RuntimeException {
    private final UUID productId;

    public CartItemNotFoundException(UUID productId) {
        super("Product " + productId + " is not in the cart");
        this.productId = productId;
    }
}
