package ua.edu.ukma.springers.voltstore.catalog.services;

import org.jspecify.annotations.Nullable;

/**
 * Result of a readiness check.
 *
 * @param reason machine-readable code when not ready (e.g. {@code stock_item_not_found},
 *               {@code packaging_data_missing}); passed through unchanged, including codes Catalog does not know
 */
public record StockReadiness(boolean ready, @Nullable String reason) {
    public static StockReadiness isReady() {
        return new StockReadiness(true, null);
    }

    public static StockReadiness notReady(String reason) {
        return new StockReadiness(false, reason);
    }
}
