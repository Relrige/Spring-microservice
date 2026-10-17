package ua.edu.ukma.springers.voltstore.order.clients;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.CatalogHttpClient;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Catalog's internal batch-get (EP-CAT-09). A read, so it is retried on connection errors, timeouts and
 * {@code 5xx}; a circuit breaker stops calling a Catalog that keeps failing, and a bulkhead limits concurrent calls.
 * Whatever goes wrong ends as {@code DependencyUnavailableException} (503), never as an empty list: the draft's
 * empty-list fallback made every product look unknown.
 */
@Component
@RequiredArgsConstructor
public class CatalogClientAdapter implements ProductCatalog {
    static final String NAME = "catalog";

    private final CatalogHttpClient client;

    // Aspect order (Resilience4j default): Retry( CircuitBreaker( Bulkhead( call ) ) ); the fallback is on the
    // outermost one, so it sees the final outcome after all retries
    @Override
    @Retry(name = NAME, fallbackMethod = "unavailable")
    @CircuitBreaker(name = NAME)
    @Bulkhead(name = NAME)
    public List<CatalogProduct> findProducts(Collection<UUID> productIds) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(productIds));
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("At most " + MAX_IDS + " products per call, got " + ids.size());
        }
        return client.batchGet(ids);
    }

    private List<CatalogProduct> unavailable(Collection<UUID> productIds, Throwable failure) {
        throw ClientFailures.unavailable("Catalog", failure);
    }
}
