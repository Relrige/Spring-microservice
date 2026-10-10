package ua.edu.ukma.springers.voltstore.inventory.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockService {
    private final StockItemRepository stockItemRepository;

    /**
     * Applies a signed delta to the available quantity and returns the new quantity.
     */
    @Transactional
    public int adjustStock(UUID productId, int delta) {
        // TEMPORARY deviation from the spec (EP-INV-02): the StockItem is created on demand without
        // checking that the product exists in Catalog. The Catalog check is added in task 19
        // (docs/tasks/inventory-service/19-catalog-product-existence-client.md).
        // If the delta is rejected below, the transaction rolls back and the row is not kept.
        stockItemRepository.insertIfAbsent(productId);

        StockItemEntity item = stockItemRepository.findByIdForUpdate(productId).orElseThrow();

        // before/after are computed inside the locked transaction so that stock events
        // (task 06) can detect threshold crossings here without restructuring.
        int before = item.getAvailableQuantity();
        int after = before + delta;
        if (after < 0) {
            throw new InsufficientStockException(productId);
        }

        item.setAvailableQuantity(after);
        return after;
    }
}
