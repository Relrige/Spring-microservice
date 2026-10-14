package ua.edu.ukma.springers.voltstore.inventory.dto;

import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;

public record PackagingResponse(int grossWeightGrams, int lengthCm, int widthCm, int heightCm) {

    /**
     * Only for items whose packaging is configured; the values are then never null.
     */
    public static PackagingResponse from(StockItemEntity item) {
        return new PackagingResponse(item.getGrossWeightGrams(), item.getLengthCm(), item.getWidthCm(),
                item.getHeightCm());
    }
}
