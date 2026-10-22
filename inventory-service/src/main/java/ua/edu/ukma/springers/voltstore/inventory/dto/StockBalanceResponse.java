package ua.edu.ukma.springers.voltstore.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record StockBalanceResponse(@Schema(requiredMode = REQUIRED) UUID productId,
                                   @Schema(requiredMode = REQUIRED) int availableQuantity,
                                   @Schema(requiredMode = REQUIRED) int reservedQuantity,
                                   @Schema(requiredMode = REQUIRED) int totalQuantity,
                                   @Schema(requiredMode = REQUIRED) boolean packagingConfigured) {

    public static StockBalanceResponse from(StockItemEntity item) {
        return new StockBalanceResponse(item.getProductId(), item.getAvailableQuantity(), item.getReservedQuantity(),
                item.getTotalQuantity(), item.isPackagingConfigured());
    }
}
