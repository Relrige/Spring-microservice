package ua.edu.ukma.springers.voltstore.inventory.exceptions;

import lombok.Getter;

@Getter
public class InsufficientStockException extends RuntimeException {
    private final String sku;
    private final Integer available;
    private final Integer requested;

    public InsufficientStockException(String sku, Integer available, Integer requested) {
        super(String.format("Insufficient stock for SKU: %s. Requested: %d, Available: %d", sku, requested, available));
        this.sku = sku;
        this.available = available;
        this.requested = requested;
    }
}