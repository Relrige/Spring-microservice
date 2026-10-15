package ua.edu.ukma.springers.voltstore.delivery.services;

import java.util.Objects;
import java.util.UUID;

/** One order line with the packaging of its product as Inventory reports it (EP-INV-05). */
public record ItemPackaging(UUID productId, int quantity, int grossWeightGrams, int lengthCm, int widthCm,
                            int heightCm) {
    public ItemPackaging {
        Objects.requireNonNull(productId, "productId must not be null");
        if (quantity <= 0 || grossWeightGrams <= 0 || lengthCm <= 0 || widthCm <= 0 || heightCm <= 0) {
            throw new IllegalArgumentException("Quantity and packaging values must be positive (product "
                    + productId + ")");
        }
    }
}
