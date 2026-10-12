package ua.edu.ukma.springers.voltstore.inventory.exceptions;

import lombok.Getter;

import java.util.UUID;

/**
 * The stock item exists, but its packaging data (gross weight and dimensions) is not fully configured.
 */
@Getter
public class PackagingNotConfiguredException extends RuntimeException {
    private final UUID productId;

    public PackagingNotConfiguredException(UUID productId) {
        super("Packaging data is not fully configured for product " + productId);
        this.productId = productId;
    }
}
