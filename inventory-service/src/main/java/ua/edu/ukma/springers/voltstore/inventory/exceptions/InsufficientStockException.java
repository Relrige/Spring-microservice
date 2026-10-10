package ua.edu.ukma.springers.voltstore.inventory.exceptions;

import lombok.Getter;

import java.util.UUID;

/**
 * Thrown when a write-off would drive the available quantity below zero.
 */
@Getter
public class InsufficientStockException extends RuntimeException {
    private final UUID productId;

    public InsufficientStockException(UUID productId) {
        super("Insufficient available stock for write-off");
        this.productId = productId;
    }
}
