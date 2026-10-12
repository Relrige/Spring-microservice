package ua.edu.ukma.springers.voltstore.catalog.exceptions;

import lombok.Getter;

import java.util.UUID;

/** Inventory says the product is not ready for activation (422, carries Inventory's reason code). */
@Getter
public class ProductActivationRejectedException extends RuntimeException {
    private final String reason;

    public ProductActivationRejectedException(UUID productId, String reason) {
        super("Product " + productId + " cannot be activated: " + reason);
        this.reason = reason;
    }
}
