package ua.edu.ukma.springers.voltstore.delivery.clients;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

import java.util.UUID;

/**
 * Declarative HTTP client for Inventory Service (internal route, authenticated with X-Internal-Token).
 * Implemented at runtime by {@code HttpServiceProxyFactory} on top of a {@code RestClient}.
 */
public interface InventoryClient {
    /**
     * EP-INV-05 (internal): the product's packaging; {@code 404} without a stock item, {@code 422} with
     * {@code reason = packaging_data_missing} when the packaging is not fully configured.
     */
    @GetExchange("/inventory/stock/{productId}/packaging")
    InventoryPackaging getPackaging(@PathVariable UUID productId);
}
