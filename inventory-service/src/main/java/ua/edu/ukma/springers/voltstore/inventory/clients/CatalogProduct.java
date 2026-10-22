package ua.edu.ukma.springers.voltstore.inventory.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One element of Catalog's batch-get answer. {@code productId} says the product is known; {@code title},
 * {@code status} and {@code version} feed the copied product data (D8). Unknown fields are ignored (tolerant
 * reader), the status is kept as text, so a new Catalog status does not break the call, and {@code version} is
 * {@code null} from a Catalog older than its task 29.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogProduct(UUID productId, String title, BigDecimal basePrice, String status, Long version) {
}
