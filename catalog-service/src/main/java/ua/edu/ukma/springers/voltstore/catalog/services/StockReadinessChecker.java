package ua.edu.ukma.springers.voltstore.catalog.services;

import ua.edu.ukma.springers.voltstore.catalog.exceptions.InventoryUnavailableException;

import java.util.UUID;

/**
 * Answers "may this product be activated?" (stock initialized and packaging data complete). The product service
 * only depends on this port; the adapter in {@code clients} asks Inventory Service over HTTP.
 */
public interface StockReadinessChecker {
    /**
     * @return ready, or not ready with Inventory's reason code (a normal business answer, not an exception)
     * @throws InventoryUnavailableException if Inventory cannot give an answer (down, slow, misconfigured)
     */
    StockReadiness check(UUID productId);
}
