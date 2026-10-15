package ua.edu.ukma.springers.voltstore.inventory.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One element of Catalog's batch-get answer. Inventory only needs {@code productId} (is the product known?);
 * unknown fields are ignored (tolerant reader), and the status is kept as text, so a new Catalog status does not
 * break the call.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogProduct(UUID productId, String title, BigDecimal basePrice, String status) {
}
