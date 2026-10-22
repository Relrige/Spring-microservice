package ua.edu.ukma.springers.voltstore.inventory.services;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.inventory.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEvents;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.PackagingNotConfiguredException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.ProductNotFoundInCatalogException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.StockItemNotFoundException;
import ua.edu.ukma.springers.voltstore.inventory.idempotency.ProcessedEvents;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.specifications.StockItemSpecifications;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class StockService {
    // Key of the ProductCreated handler in processed_events
    static final String PRODUCT_CREATED_CONSUMER = "product-created";

    private final StockItemRepository stockItemRepository;
    private final StockEventPublisher stockEventPublisher;
    private final ProcessedEvents processedEvents;
    private final ProductCatalog productCatalog;
    // Adjust and packaging ask Catalog first and only then open the locking transaction (see requireKnownProduct)
    private final TransactionTemplate transactionTemplate;

    public StockService(StockItemRepository stockItemRepository, StockEventPublisher stockEventPublisher,
                        ProcessedEvents processedEvents, ProductCatalog productCatalog,
                        PlatformTransactionManager transactionManager) {
        this.stockItemRepository = stockItemRepository;
        this.stockEventPublisher = stockEventPublisher;
        this.processedEvents = processedEvents;
        this.productCatalog = productCatalog;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

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
     * Applies a signed delta to the available quantity and returns the new quantity. A product without a StockItem
     * must exist in Catalog ({@link #requireKnownProduct}).
     */
    public int adjustStock(UUID productId, int delta) {
        CatalogProductData fromCatalog = requireKnownProduct(productId);
        return transactionTemplate.execute(status -> {
            // If the delta is rejected below, the transaction rolls back and a row created here is not kept.
            StockItemEntity item = getOrCreateForUpdate(productId, fromCatalog);

            // before/after are computed inside the locked transaction, so the zero-crossing check below
            // sees a consistent pair. The event is written to the outbox in this same transaction.
            int before = item.getAvailableQuantity();
            int after = before + delta;
            if (after < 0) {
                throw new InsufficientStockException(productId);
            }

            item.setAvailableQuantity(after);
            StockEvents.forTransition(productId, before, after).ifPresent(stockEventPublisher::publish);
            return after;
        });
    }

    /**
     * Replaces all four packaging values at once. Quantities are untouched and no event is emitted. A product
     * without a StockItem must exist in Catalog, as for {@link #adjustStock}.
     */
    public void setPackaging(UUID productId, int grossWeightGrams, int lengthCm, int widthCm, int heightCm) {
        CatalogProductData fromCatalog = requireKnownProduct(productId);
        transactionTemplate.executeWithoutResult(status -> {
            StockItemEntity item = getOrCreateForUpdate(productId, fromCatalog);
            item.setGrossWeightGrams(grossWeightGrams);
            item.setLengthCm(lengthCm);
            item.setWidthCm(widthCm);
            item.setHeightCm(heightCm);
        });
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

    /**
     * EP-INV-EVT-01 as an Idempotent Consumer: the event ID is recorded in the same transaction as the StockItem
     * insert and the copy of the product data, so a redelivered {@code ProductCreated} (same {@code eventId}) is
     * recognised and changes nothing. See {@link #storeProductData} for what is written.
     */
    @Transactional
    public ProductCreatedResult applyProductCreated(ProductEvent event) {
        if (!processedEvents.markProcessed(event.eventId(), PRODUCT_CREATED_CONSUMER)) {
            return ProductCreatedResult.DUPLICATE_EVENT;
        }
        boolean changed = storeProductData(event.productId(), event.title(), event.status(), event.version(),
                ProductEvent.CREATED);
        return changed ? ProductCreatedResult.APPLIED : ProductCreatedResult.UNCHANGED;
    }

    public enum ProductCreatedResult {
        // The StockItem was created, or its copy of the product data updated
        APPLIED,
        // The StockItem existed (created by a worker's adjust/packaging or by ProductUpdated) with the same or a
        // newer copy; a different event, nothing to do
        UNCHANGED,
        // This eventId was already applied: a redelivery
        DUPLICATE_EVENT
    }

    /**
     * EP-INV-EVT-03: creates a missing StockItem and updates the copy of the product data if the event is newer.
     * Idempotent by state (the version check), so no {@code processed_events} key is needed. The caller has checked
     * that title, status and version are present.
     *
     * @return whether the row was created or its copy updated
     */
    @Transactional
    public boolean applyProductUpdated(ProductEvent event) {
        return storeProductData(event.productId(), event.title(), event.status(), event.version(),
                ProductEvent.UPDATED);
    }

    /**
     * EP-INV-JOB-01: writes the product data Catalog's batch-get returned, in a short transaction of its own. An event
     * applied in the meantime wins if it is newer (the version check).
     *
     * @return whether the copy was written
     */
    @Transactional
    public boolean applyCatalogProductData(CatalogProductData product) {
        return storeProductData(product.productId(), product.title(), product.status(), product.version(),
                "Product data backfill");
    }

    /**
     * Creates the StockItem if it is missing and writes the copy of the product data with the version-guarded upsert
     * (D8), so an older or repeated state changes nothing. Without a version (a source older than Catalog's task 29),
     * without a title, or with a status Inventory does not know, only a missing row is created and the copy is left
     * for the backfill (EP-INV-JOB-01). Runs in the caller's transaction.
     *
     * @param source where the values come from, for the log
     * @return whether the row was created or its copy updated
     */
    private boolean storeProductData(UUID productId, @Nullable String title, @Nullable String status,
                                     @Nullable Long version, String source) {
        Optional<ProductStatus> productStatus = ProductStatus.parse(status);
        if (version != null && title != null && productStatus.isPresent()) {
            return stockItemRepository.upsertProductData(productId, title, productStatus.get().name(), version) > 0;
        }
        if (version != null) {
            // An old source simply has no version; a versioned one without a usable title or status is unexpected
            log.warn("{} for product {} (version {}) has {}; the copy of the product data is not written", source,
                    productId, version, title == null ? "no title" : "an unknown status '" + status + "'");
        }
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
     * EP-INV-02/03: a StockItem is created on demand only for a product that Catalog knows. Normally the
     * ProductCreated event has created it already and Catalog is not called at all; this synchronous check is the
     * fallback for a missed event.
     * <p>
     * Runs <b>before</b> the transaction that locks the stock row, so no database connection or row lock is held
     * while waiting for Catalog. If someone else creates the StockItem in between, nothing breaks: the creation in
     * {@link #getOrCreateForUpdate} is race-safe.
     *
     * @return Catalog's answer for a product without a StockItem, whose data is copied when the row is created;
     *         {@code null} if the StockItem exists (Catalog was not called)
     * @throws ProductNotFoundInCatalogException if Catalog does not know the product (nothing is created)
     * @throws ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException if Catalog cannot
     *         answer (nothing is changed)
     */
    private @Nullable CatalogProductData requireKnownProduct(UUID productId) {
        if (stockItemRepository.existsById(productId)) {
            return null;
        }
        return productCatalog.find(productId).orElseThrow(() -> new ProductNotFoundInCatalogException(productId));
    }

    /**
     * Returns the row-locked StockItem, creating it (with zero quantities and no packaging) if it is absent; the
     * caller has checked the product against Catalog ({@link #requireKnownProduct}). A row created from Catalog's
     * answer gets the copy of the product data with it. Concurrent first-time callers end up with exactly one row;
     * the lock serializes their updates.
     */
    private StockItemEntity getOrCreateForUpdate(UUID productId, @Nullable CatalogProductData fromCatalog) {
        if (fromCatalog == null) {
            stockItemRepository.insertIfAbsent(productId);
        } else {
            storeProductData(productId, fromCatalog.title(), fromCatalog.status(), fromCatalog.version(),
                    "Catalog existence check");
        }
        return stockItemRepository.findByIdForUpdate(productId).orElseThrow();
    }
}
