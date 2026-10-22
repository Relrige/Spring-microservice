package ua.edu.ukma.springers.voltstore.inventory.clients;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException;
import ua.edu.ukma.springers.voltstore.inventory.services.CatalogProductData;
import ua.edu.ukma.springers.voltstore.inventory.services.ProductCatalog;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Asks Catalog's internal batch-get for products: whether they exist, and their data for the copy in the stock row.
 * <ul>
 *   <li>The answer lists the products Catalog knows; a product missing from it does not exist. Both are business
 *   answers, never retried.</li>
 *   <li>Connection error, timeout or {@code 5xx} → transient: retried {@code maxRetries} times with jittered delay
 *   (task 20), then {@link CatalogUnavailableException} (503).</li>
 *   <li>Any other status (e.g. {@code 401}/{@code 403}: wrong internal token or route rule) → configuration
 *   problem, logged as an error, not retried, {@link CatalogUnavailableException}.</li>
 * </ul>
 * Each failed attempt is logged once; the internal token never appears in a log line or exception message.
 */
@Slf4j
@Component
public class CatalogProductCatalog implements ProductCatalog {
    private final CatalogClient catalogClient;
    private final RetryTemplate retryTemplate;

    public CatalogProductCatalog(CatalogClient catalogClient, CatalogClientProperties properties) {
        this.catalogClient = catalogClient;
        // Spring Framework 7's built-in retry (no extra library); only TransientFailure is retried
        Duration retryDelay = properties.retryDelay();
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(properties.maxRetries())
                .delay(retryDelay)
                .jitter(retryDelay.dividedBy(2))
                .includes(TransientFailure.class)
                .build());
    }

    @Override
    public List<CatalogProductData> findAll(Collection<UUID> productIds) {
        List<UUID> ids = List.copyOf(productIds);
        try {
            return retryTemplate.execute(() -> findAllOnce(ids));
        } catch (RetryException e) {
            Throwable last = e.getLastException();
            if (last instanceof CatalogUnavailableException unavailable) {
                throw unavailable;
            }
            throw new CatalogUnavailableException("Catalog Service is unavailable", last);
        }
    }

    private List<CatalogProductData> findAllOnce(List<UUID> productIds) {
        // One ID is the existence check of EP-INV-02/03; more are the backfill (EP-INV-JOB-01)
        String what = productIds.size() == 1 ? "product " + productIds.getFirst() : productIds.size() + " products";
        try {
            List<CatalogProduct> answer = catalogClient.batchGet(productIds);
            if (answer == null) {
                return List.of();
            }
            return answer.stream()
                    .filter(product -> product.productId() != null)
                    .map(product -> new CatalogProductData(product.productId(), product.title(), product.status(),
                            product.version()))
                    .toList();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                log.warn("Catalog lookup of {} failed: Catalog answered {}", what, e.getStatusCode());
                throw new TransientFailure(e);
            }
            log.error("Catalog lookup of {}: unexpected status {} from Catalog "
                    + "(check the internal token and Catalog's route rules)", what, e.getStatusCode());
            throw new CatalogUnavailableException("Catalog answered " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // Connection refused, connect or read timeout, broken response
            log.warn("Catalog lookup of {} failed: {}", what, e.getMessage());
            throw new TransientFailure(e);
        }
    }

    // A failure worth retrying (the next attempt may succeed)
    private static final class TransientFailure extends RuntimeException {
        TransientFailure(Throwable cause) {
            super(cause);
        }
    }
}
