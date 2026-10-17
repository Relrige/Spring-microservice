package ua.edu.ukma.springers.voltstore.order.ports;

import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Catalog Service: current title, price, status and stock status of products. */
public interface ProductCatalog {
    /** Catalog's limit per call; an order has at most this many lines. */
    int MAX_IDS = 100;

    /**
     * The products that exist, in any status; unknown IDs are simply missing from the result.
     *
     * @throws DependencyUnavailableException Catalog could not answer. Never reported as "no products": that would
     *                                        make every product look unknown
     */
    List<CatalogProduct> findProducts(Collection<UUID> productIds);
}
