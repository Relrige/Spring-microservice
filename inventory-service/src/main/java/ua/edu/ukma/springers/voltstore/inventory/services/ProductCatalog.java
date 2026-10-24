package ua.edu.ukma.springers.voltstore.inventory.services;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What Inventory needs to know from Catalog: whether products exist, and their data for the copy in the stock row
 * (D8). Hides the HTTP client from the services (implemented by {@code clients.CatalogProductCatalog}).
 */
public interface ProductCatalog {
    /**
     * @return the product (in any status), or empty if Catalog does not know it
     * @throws ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException if Catalog cannot
     *         answer (unreachable, timeout, server error, unexpected response)
     */
    default Optional<CatalogProductData> find(UUID productId) {
        return findAll(List.of(productId)).stream().filter(product -> productId.equals(product.productId()))
                .findFirst();
    }

    /**
     * @param productIds at most 100 (Catalog's batch-get limit)
     * @return the products Catalog knows, in any order; unknown IDs are simply missing
     * @throws ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException as for {@link #find}
     */
    List<CatalogProductData> findAll(Collection<UUID> productIds);
}
