package ua.edu.ukma.springers.voltstore.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import ua.edu.ukma.springers.voltstore.inventory.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// EP-INV-08: one stock row (as in EP-INV-01) plus the stored packaging values, null while a value is not set
public record StockItemResponse(@Schema(requiredMode = REQUIRED) UUID productId,
                                @Schema(description = "Catalog's product title; missing until copied")
                                @Nullable String title,
                                @Schema(description = "Catalog's product status; missing until copied")
                                @Nullable ProductStatus productStatus,
                                @Schema(requiredMode = REQUIRED) int availableQuantity,
                                @Schema(requiredMode = REQUIRED) int reservedQuantity,
                                @Schema(requiredMode = REQUIRED) int totalQuantity,
                                @Schema(requiredMode = REQUIRED) boolean packagingConfigured,
                                @Schema(description = "Gross weight in grams; missing until set") @Nullable Integer grossWeightGrams,
                                @Schema(description = "Length in cm; missing until set") @Nullable Integer lengthCm,
                                @Schema(description = "Width in cm; missing until set") @Nullable Integer widthCm,
                                @Schema(description = "Height in cm; missing until set") @Nullable Integer heightCm) {

    public static StockItemResponse from(StockItemEntity item) {
        return new StockItemResponse(item.getProductId(), item.getTitle(), item.getProductStatus(),
                item.getAvailableQuantity(), item.getReservedQuantity(), item.getTotalQuantity(),
                item.isPackagingConfigured(), item.getGrossWeightGrams(), item.getLengthCm(), item.getWidthCm(),
                item.getHeightCm());
    }
}
