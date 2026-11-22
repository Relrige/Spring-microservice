package ua.edu.ukma.springers.voltstore.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.math.BigDecimal;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// One row of the product listing (EP-CAT-04); the same shape for customers and managers
public record ProductSummaryResponse(
        @Schema(requiredMode = REQUIRED)
        UUID productId,
        @Schema(requiredMode = REQUIRED)
        String title,
        @Schema(requiredMode = REQUIRED)
        BigDecimal basePrice,
        @Schema(requiredMode = REQUIRED)
        UUID categoryId,
        @Schema(requiredMode = REQUIRED)
        ProductStatus status,
        @Schema(requiredMode = REQUIRED)
        StockStatus stockStatus,
        // null when the product has no image (task 30)
        String imageUrl
) {
    public static ProductSummaryResponse from(Product product) {
        return new ProductSummaryResponse(product.getId(), product.getTitle(), product.getBasePrice(),
                product.getCategoryId(), product.getStatus(), product.getStockStatus(), product.getImageUrl());
    }
}
