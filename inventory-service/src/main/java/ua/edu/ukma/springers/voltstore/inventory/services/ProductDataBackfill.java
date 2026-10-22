package ua.edu.ukma.springers.voltstore.inventory.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;

import java.util.List;
import java.util.UUID;

/**
 * EP-INV-JOB-01: fills the copied product data of stock rows that have none (D9): rows from before the copy
 * existed, and rows created from an event or a Catalog answer without a version. New and updated products get
 * their copy from the product events; this job only heals the gaps.
 * <p>
 * Each run reads up to {@value #BATCH_SIZE} IDs without a title, asks Catalog's batch-get for them, and writes each
 * answer through the same version-guarded upsert as the events, then continues after the last ID, at most
 * {@value #MAX_BATCHES} batches. No transaction is open during the Catalog call, and no lock is needed between
 * replicas: the upsert is idempotent and keeps the newest version, so two replicas running at once is harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductDataBackfill {
    // Catalog's batch-get limit
    static final int BATCH_SIZE = 100;
    // Keeps one run short (at most 1000 rows); the rest waits for the next run
    static final int MAX_BATCHES = 10;
    // Below every random UUID, so the first batch starts at the beginning
    private static final UUID START = new UUID(0, 0);

    private final StockItemRepository stockItemRepository;
    private final ProductCatalog productCatalog;
    private final StockService stockService;

    /**
     * Runs one pass. Called by the scheduler; tests call it directly.
     *
     * @return the number of rows whose copy was written
     */
    @Scheduled(fixedDelayString = "${inventory.product-data-backfill.interval}",
            initialDelayString = "${inventory.product-data-backfill.interval}")
    public int fillMissingProductData() {
        int filled = 0;
        int requested = 0;
        int unknown = 0;
        UUID after = START;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<UUID> ids = stockItemRepository.findIdsWithoutTitleAfter(after, Limit.of(BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            List<CatalogProductData> products;
            try {
                products = productCatalog.findAll(ids);
            } catch (CatalogUnavailableException e) {
                log.warn("Product data backfill stopped: Catalog is unavailable ({}); the next run tries again",
                        e.getMessage());
                break;
            }
            filled += apply(products);
            requested += ids.size();
            unknown += ids.size() - (int) products.stream().map(CatalogProductData::productId)
                    .filter(ids::contains).distinct().count();
            if (ids.size() < BATCH_SIZE) {
                break;
            }
            after = ids.getLast();
        }
        if (unknown > 0) {
            // Should not happen: stock rows exist only for Catalog products
            log.warn("Product data backfill: Catalog does not know {} of {} requested products; their rows stay "
                    + "without product data", unknown, requested);
        }
        if (filled > 0) {
            log.info("Product data backfill filled {} stock rows", filled);
        }
        return filled;
    }

    private int apply(List<CatalogProductData> products) {
        int filled = 0;
        for (CatalogProductData product : products) {
            // A Catalog older than its task 29 sends no version: nothing to copy safely, the row stays null
            if (product.version() != null && stockService.applyCatalogProductData(product)) {
                filled++;
            }
        }
        return filled;
    }
}
