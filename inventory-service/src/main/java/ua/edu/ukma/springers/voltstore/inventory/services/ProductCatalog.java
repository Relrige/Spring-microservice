package ua.edu.ukma.springers.voltstore.inventory.services;

import java.util.UUID;

/**
 * What Inventory needs to know from Catalog: whether a product exists. Hides the HTTP client from the services
 * (implemented by {@code clients.CatalogProductCatalog}).
 */
public interface ProductCatalog {
    /**
     * @return {@code true} if Catalog knows the product (in any status), {@code false} if it does not
     * @throws ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException if Catalog cannot
     *         answer (unreachable, timeout, server error, unexpected response)
     */
    boolean exists(UUID productId);
}
