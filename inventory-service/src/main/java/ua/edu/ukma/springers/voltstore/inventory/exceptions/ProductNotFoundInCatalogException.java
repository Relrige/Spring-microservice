package ua.edu.ukma.springers.voltstore.inventory.exceptions;

import lombok.Getter;

import java.util.UUID;

@Getter
public class ProductNotFoundInCatalogException extends RuntimeException {
    private final UUID productId;

    public ProductNotFoundInCatalogException(UUID productId) {
        super("Product " + productId + " not found in Catalog");
        this.productId = productId;
    }
}
