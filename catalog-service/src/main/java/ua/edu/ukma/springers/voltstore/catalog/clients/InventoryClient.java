package ua.edu.ukma.springers.voltstore.catalog.clients;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

import java.util.UUID;

/**
 * Declarative HTTP client for Inventory Service (internal routes, authenticated with X-Internal-Token).
 * Implemented at runtime by {@code HttpServiceProxyFactory} on top of a {@code RestClient}.
 */
public interface InventoryClient {
    /**
     * EP-INV-04: {@code 204} = ready. {@code 404} (no StockItem) and {@code 422 { reason }} are thrown as
     * {@code RestClientResponseException} by the RestClient and read as business answers by the adapter.
     */
    @GetExchange("/inventory/stock/{productId}/readiness")
    ResponseEntity<Void> checkReadiness(@PathVariable UUID productId);
}
