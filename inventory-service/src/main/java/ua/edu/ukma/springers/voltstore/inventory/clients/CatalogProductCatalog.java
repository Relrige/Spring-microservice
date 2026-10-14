package ua.edu.ukma.springers.voltstore.inventory.clients;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException;
import ua.edu.ukma.springers.voltstore.inventory.services.ProductCatalog;

import java.util.List;
import java.util.UUID;

/**
 * Asks Catalog's internal batch-get whether a product exists.
 * <ul>
 *   <li>The product is in the answer → {@code true}; an empty answer (or one without it) → {@code false}. Both are
 *   business answers.</li>
 *   <li>Connection error, timeout or {@code 5xx} → {@link CatalogUnavailableException} (503).</li>
 *   <li>Any other status (e.g. {@code 401}/{@code 403}: wrong internal token or route rule) → configuration
 *   problem, logged as an error, {@link CatalogUnavailableException}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogProductCatalog implements ProductCatalog {
    private final CatalogClient catalogClient;

    @Override
    public boolean exists(UUID productId) {
        try {
            List<CatalogProduct> products = catalogClient.batchGet(List.of(productId));
            return products != null && products.stream().anyMatch(product -> productId.equals(product.productId()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                log.warn("Catalog lookup of product {} failed: Catalog answered {}", productId, e.getStatusCode());
            } else {
                log.error("Catalog lookup of product {}: unexpected status {} from Catalog "
                        + "(check the internal token and Catalog's route rules)", productId, e.getStatusCode());
            }
            throw new CatalogUnavailableException("Catalog answered " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // Connection refused, connect or read timeout, broken response
            log.warn("Catalog lookup of product {} failed: {}", productId, e.getMessage());
            throw new CatalogUnavailableException("Catalog Service is unavailable", e);
        }
    }
}
