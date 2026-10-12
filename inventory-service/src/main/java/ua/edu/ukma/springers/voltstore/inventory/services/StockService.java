package ua.edu.ukma.springers.voltstore.inventory.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEvents;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.PackagingNotConfiguredException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.StockItemNotFoundException;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.specifications.StockItemSpecifications;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockService {
    private final StockItemRepository stockItemRepository;
    private final StockEventPublisher stockEventPublisher;

    /**
     * Lists stock items one page at a time. Filters are optional ({@code null} means "do not filter") and combine
     * with AND; ordering comes from the pageable.
     */
    @Transactional(readOnly = true)
    public Page<StockItemEntity> listStock(Boolean packagingConfigured, Pageable pageable) {
        Specification<StockItemEntity> spec = Specification.unrestricted();
        if (packagingConfigured != null) {
            spec = spec.and(StockItemSpecifications.packagingConfigured(packagingConfigured));
        }
        return stockItemRepository.findAll(spec, pageable);
    }

    /**
     * Applies a signed delta to the available quantity and returns the new quantity.
     */
    @Transactional
    public int adjustStock(UUID productId, int delta) {
        // If the delta is rejected below, the transaction rolls back and a row created here is not kept.
        StockItemEntity item = getOrCreateForUpdate(productId);

        // before/after are computed inside the locked transaction, so the zero-crossing check below
        // sees a consistent pair. The publisher delivers the event only after this transaction commits.
        int before = item.getAvailableQuantity();
        int after = before + delta;
        if (after < 0) {
            throw new InsufficientStockException(productId);
        }

        item.setAvailableQuantity(after);
        StockEvents.forTransition(productId, before, after).ifPresent(stockEventPublisher::publish);
        return after;
    }

    /**
     * Replaces all four packaging values at once. Quantities are untouched and no event is emitted.
     */
    @Transactional
    public void setPackaging(UUID productId, int grossWeightGrams, int lengthCm, int widthCm, int heightCm) {
        StockItemEntity item = getOrCreateForUpdate(productId);
        item.setGrossWeightGrams(grossWeightGrams);
        item.setLengthCm(lengthCm);
        item.setWidthCm(widthCm);
        item.setHeightCm(heightCm);
    }

    /**
     * Succeeds only if the StockItem exists and its packaging is fully configured; used by Catalog before a
     * product is activated. Read-only: a missing StockItem is reported, never created, and no event is emitted.
     */
    @Transactional(readOnly = true)
    public void checkReadiness(UUID productId) {
        getWithConfiguredPackaging(productId);
    }

    /**
     * Returns the StockItem whose packaging values Delivery needs for TTN generation. Same outcomes as
     * {@link #checkReadiness}: missing StockItem or incomplete packaging is reported; nothing is created or emitted.
     */
    @Transactional(readOnly = true)
    public StockItemEntity getPackaging(UUID productId) {
        return getWithConfiguredPackaging(productId);
    }

    /**
     * EP-INV-EVT-01: makes sure a StockItem exists for a product Catalog just created (zero quantities, no
     * packaging). Race-safe against a duplicate event and against adjust/packaging creating the row at the same
     * time ({@code INSERT … ON CONFLICT DO NOTHING}); an existing row is never touched. Emits no event.
     *
     * @return whether a new StockItem was created
     */
    @Transactional
    public boolean ensureStockItem(UUID productId) {
        return stockItemRepository.insertIfAbsent(productId) > 0;
    }

    private StockItemEntity getWithConfiguredPackaging(UUID productId) {
        StockItemEntity item = stockItemRepository.findById(productId)
                .orElseThrow(() -> new StockItemNotFoundException(productId));
        if (!item.isPackagingConfigured()) {
            throw new PackagingNotConfiguredException(productId);
        }
        return item;
    }

    /**
     * Returns the row-locked StockItem, creating it (with zero quantities and no packaging) if it is absent.
     * Concurrent first-time callers end up with exactly one row; the lock serializes their updates.
     * <p>
     * TEMPORARY deviation from the spec (EP-INV-02, EP-INV-03): the StockItem is created on demand without
     * checking that the product exists in Catalog. The Catalog check is added here, in this one place, in task 19
     * (docs/tasks/inventory-service/19-catalog-product-existence-client.md).
     */
    private StockItemEntity getOrCreateForUpdate(UUID productId) {
        stockItemRepository.insertIfAbsent(productId);
        return stockItemRepository.findByIdForUpdate(productId).orElseThrow();
    }
}
