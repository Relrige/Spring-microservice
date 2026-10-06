package ua.edu.ukma.springers.voltstore.order.clients;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.order.clients.dto.ProductDto;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ResilientCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientCatalogClient.class);
    private final CatalogClient catalogClient;

    @Retry(name = "catalogClient", fallbackMethod = "getProductsFallback")
    @CircuitBreaker(name = "catalogClient")
    @Bulkhead(name = "catalogClient", type = Bulkhead.Type.SEMAPHORE)
    public List<ProductDto> getProductsBatchSafely(List<UUID> ids) {
        log.info("Fetching batch of products from catalog for {} items", ids.size());
        return catalogClient.getProductsByIds(ids);
    }

    public List<ProductDto> getProductsFallback(List<UUID> ids, Throwable t) {
        log.error("Catalog Service is unavailable. Fallback activated! Reason: {}", t.getMessage());
        return Collections.emptyList();
    }
}