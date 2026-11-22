package ua.edu.ukma.springers.voltstore.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.math.BigDecimal;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// Full view of a product: EP-CAT-05 for every caller, and the answer of EP-CAT-02
public record ProductResponse(
        @Schema(requiredMode = REQUIRED)
        UUID productId,
        @Schema(requiredMode = REQUIRED)
        String title,
        @Schema(requiredMode = REQUIRED)
        String description,
        @Schema(requiredMode = REQUIRED)
        UUID categoryId,
        @Schema(requiredMode = REQUIRED)
        BigDecimal basePrice,
        @Schema(requiredMode = REQUIRED)
        ProductStatus status,
        @Schema(requiredMode = REQUIRED)
        StockStatus stockStatus,
        // null when the product has no image (task 30)
        String imageUrl
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getTitle(), product.getDescription(),
                product.getCategoryId(), product.getBasePrice(), product.getStatus(), product.getStockStatus(),
                product.getImageUrl());
    }
}
