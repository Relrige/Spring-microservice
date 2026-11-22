package ua.edu.ukma.springers.voltstore.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.math.BigDecimal;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// One element of the internal batch lookup (EP-CAT-09), used by Order Service for price snapshots and its checkout
// soft check, and by Inventory to fill its copy of product data. stockStatus is the stored read model of Inventory's
// stock (eventually consistent, no quantities); version is the product's version, as in the product events.
public record BatchProductResponse(
        @Schema(requiredMode = REQUIRED)
        UUID productId,
        @Schema(requiredMode = REQUIRED)
        String title,
        @Schema(requiredMode = REQUIRED)
        BigDecimal basePrice,
        @Schema(requiredMode = REQUIRED)
        ProductStatus status,
        @Schema(requiredMode = REQUIRED)
        StockStatus stockStatus,
        @Schema(requiredMode = REQUIRED)
        long version
) {
    public static BatchProductResponse from(Product product) {
        return new BatchProductResponse(product.getId(), product.getTitle(), product.getBasePrice(),
                product.getStatus(), product.getStockStatus(), product.getVersion());
    }
}
