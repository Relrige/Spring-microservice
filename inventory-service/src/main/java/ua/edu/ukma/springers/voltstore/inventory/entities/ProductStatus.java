package ua.edu.ukma.springers.voltstore.inventory.entities;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;

/**
 * Catalog's product status, as copied into the stock row (D8). Inventory's own enum (no shared library); Catalog
 * owns the values.
 */
public enum ProductStatus {
    ACTIVE,
    NOT_ACTIVE;

    /**
     * Tolerant reader: Catalog sends the status as text, and a value Inventory does not know yet is not an error.
     *
     * @return the status, or empty for {@code null} or an unknown value
     */
    public static Optional<ProductStatus> parse(@Nullable String value) {
        return Arrays.stream(values()).filter(status -> status.name().equals(value)).findFirst();
    }
}
