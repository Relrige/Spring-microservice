package ua.edu.ukma.springers.voltstore.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record PackagingResponse(@Schema(requiredMode = REQUIRED) int grossWeightGrams,
                                @Schema(requiredMode = REQUIRED) int lengthCm,
                                @Schema(requiredMode = REQUIRED) int widthCm,
                                @Schema(requiredMode = REQUIRED) int heightCm) {

    /**
     * Only for items whose packaging is configured; the values are then never null.
     */
    public static PackagingResponse from(StockItemEntity item) {
        return new PackagingResponse(item.getGrossWeightGrams(), item.getLengthCm(), item.getWidthCm(),
                item.getHeightCm());
    }
}
