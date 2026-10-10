package ua.edu.ukma.springers.voltstore.inventory.dto;

import jakarta.validation.constraints.NotNull;
import ua.edu.ukma.springers.voltstore.inventory.validation.NonZero;

/**
 * @param delta positive to restock, negative to write off
 */
public record AdjustStockRequest(
        @NotNull(message = "Delta is required")
        @NonZero(message = "Delta must be non-zero")
        Integer delta
) {
}
