package ua.edu.ukma.springers.voltstore.inventory.clients;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

import java.util.List;
import java.util.UUID;

/**
 * Declarative HTTP client for Catalog Service (internal routes, authenticated with X-Internal-Token).
 * Implemented at runtime by {@code HttpServiceProxyFactory} on top of a {@code RestClient}.
 */
public interface CatalogClient {
    /**
     * EP-CAT-09 (internal): the products with the given IDs, in any status; unknown IDs are simply missing from the
     * result. Used instead of the public {@code GET /catalog/products/{id}}, which answers {@code 404} for products
     * that are not ACTIVE yet, while restocking before activation is the main use case.
     */
    @GetExchange("/catalog/products/batch-get")
    List<CatalogProduct> batchGet(@RequestParam("ids") List<UUID> ids);
}
