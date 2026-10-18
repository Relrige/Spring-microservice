package ua.edu.ukma.springers.voltstore.order.ports;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A product as Catalog's internal batch-get returns it.
 *
 * @param stockStatus Catalog's read model of Inventory's stock; {@code null} while Catalog does not return it yet
 *                    (Catalog task 24)
 */
public record CatalogProduct(UUID productId, String title, BigDecimal basePrice, Status status,
                             StockStatus stockStatus) {

    public enum Status {
        ACTIVE,
        NOT_ACTIVE
    }

    public enum StockStatus {
        IN_STOCK,
        OUT_OF_STOCK
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
