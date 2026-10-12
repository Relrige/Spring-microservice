package ua.edu.ukma.springers.voltstore.inventory.exceptions;

import lombok.Getter;

import java.util.UUID;

@Getter
public class StockItemNotFoundException extends RuntimeException {
    private final UUID productId;

    public StockItemNotFoundException(UUID productId) {
        super("Stock item not found for product " + productId);
        this.productId = productId;
    }
}
