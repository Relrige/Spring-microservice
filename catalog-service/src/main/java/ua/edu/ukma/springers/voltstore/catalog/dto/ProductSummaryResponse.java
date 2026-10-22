package ua.edu.ukma.springers.voltstore.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.math.BigDecimal;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// One row of the product listing (EP-CAT-04); the same shape for customers and managers
@Data
@AllArgsConstructor
public class ProductSummaryResponse {
    @Schema(requiredMode = REQUIRED)
    private UUID productId;
    @Schema(requiredMode = REQUIRED)
    private String title;
    @Schema(requiredMode = REQUIRED)
    private BigDecimal basePrice;
    @Schema(requiredMode = REQUIRED)
    private UUID categoryId;
    @Schema(requiredMode = REQUIRED)
    private ProductStatus status;
    @Schema(requiredMode = REQUIRED)
    private StockStatus stockStatus;

    public static ProductSummaryResponse from(Product product) {
        return new ProductSummaryResponse(product.getId(), product.getTitle(), product.getBasePrice(),
                product.getCategoryId(), product.getStatus(), product.getStockStatus());
    }
}
