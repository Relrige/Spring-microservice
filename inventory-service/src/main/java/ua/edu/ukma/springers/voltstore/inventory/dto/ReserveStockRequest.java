package ua.edu.ukma.springers.voltstore.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ua.edu.ukma.springers.voltstore.inventory.validation.UniqueProductIds;

import java.util.List;
import java.util.UUID;

/**
 * @param items one entry per product; a product listed twice is rejected, not merged
 */
public record ReserveStockRequest(
        @NotNull(message = "Order id is required")
        UUID orderId,

        @NotEmpty(message = "Items must not be empty")
        @Size(max = ReserveStockRequest.MAX_ITEMS, message = "At most " + ReserveStockRequest.MAX_ITEMS + " items are allowed")
        @UniqueProductIds
        List<@NotNull(message = "Item must not be null") @Valid ReserveStockItem> items
) {
    public static final int MAX_ITEMS = 100;
}
