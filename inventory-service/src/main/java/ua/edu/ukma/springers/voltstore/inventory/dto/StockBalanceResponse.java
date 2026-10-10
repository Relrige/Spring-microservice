package ua.edu.ukma.springers.voltstore.inventory.dto;

import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;

import java.util.UUID;

public record StockBalanceResponse(UUID productId, int availableQuantity, int reservedQuantity, int totalQuantity,
                                   boolean packagingConfigured) {

    public static StockBalanceResponse from(StockItemEntity item) {
        return new StockBalanceResponse(item.getProductId(), item.getAvailableQuantity(), item.getReservedQuantity(),
                item.getTotalQuantity(), item.isPackagingConfigured());
    }
}
