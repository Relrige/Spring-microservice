package ua.edu.ukma.springers.voltstore.inventory.clients;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException;
import ua.edu.ukma.springers.voltstore.inventory.services.ProductCatalog;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Asks Catalog's internal batch-get whether a product exists.
 * <ul>
 *   <li>The product is in the answer → {@code true}; an empty answer (or one without it) → {@code false}. Both are
 *   business answers, never retried.</li>
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
    public boolean exists(UUID productId) {
        try {
            return retryTemplate.execute(() -> existsOnce(productId));
        } catch (RetryException e) {
            Throwable last = e.getLastException();
            if (last instanceof CatalogUnavailableException unavailable) {
                throw unavailable;
            }
            throw new CatalogUnavailableException("Catalog Service is unavailable", last);
        }
    }

    private boolean existsOnce(UUID productId) {
        try {
            List<CatalogProduct> products = catalogClient.batchGet(List.of(productId));
            return products != null && products.stream().anyMatch(product -> productId.equals(product.productId()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                log.warn("Catalog lookup of product {} failed: Catalog answered {}", productId, e.getStatusCode());
                throw new TransientFailure(e);
            }
            log.error("Catalog lookup of product {}: unexpected status {} from Catalog "
                    + "(check the internal token and Catalog's route rules)", productId, e.getStatusCode());
            throw new CatalogUnavailableException("Catalog answered " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // Connection refused, connect or read timeout, broken response
            log.warn("Catalog lookup of product {} failed: {}", productId, e.getMessage());
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
