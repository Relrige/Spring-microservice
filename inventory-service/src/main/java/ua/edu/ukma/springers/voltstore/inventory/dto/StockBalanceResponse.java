package ua.edu.ukma.springers.voltstore.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import ua.edu.ukma.springers.voltstore.inventory.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// title and productStatus are the copy of Catalog's data (D8): optional (D5), absent until copied
public record StockBalanceResponse(@Schema(requiredMode = REQUIRED) UUID productId,
                                   @Schema(description = "Catalog's product title; missing until copied")
                                   @Nullable String title,
                                   @Schema(description = "Catalog's product status; missing until copied")
                                   @Nullable ProductStatus productStatus,
                                   @Schema(requiredMode = REQUIRED) int availableQuantity,
                                   @Schema(requiredMode = REQUIRED) int reservedQuantity,
                                   @Schema(requiredMode = REQUIRED) int totalQuantity,
                                   @Schema(requiredMode = REQUIRED) boolean packagingConfigured) {

    public static StockBalanceResponse from(StockItemEntity item) {
        return new StockBalanceResponse(item.getProductId(), item.getTitle(), item.getProductStatus(),
                item.getAvailableQuantity(), item.getReservedQuantity(), item.getTotalQuantity(),
                item.isPackagingConfigured());
    }
}
