package ua.edu.ukma.springers.voltstore.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.math.BigDecimal;
import java.util.UUID;

// One row of the product listing (EP-CAT-04); the same shape for customers and managers
@Data
@AllArgsConstructor
public class ProductSummaryResponse {
    private UUID productId;
    private String title;
    private BigDecimal basePrice;
    private UUID categoryId;
    private ProductStatus status;
    private StockStatus stockStatus;

    public static ProductSummaryResponse from(Product product) {
        return new ProductSummaryResponse(product.getId(), product.getTitle(), product.getBasePrice(),
                product.getCategoryId(), product.getStatus(), product.getStockStatus());
    }
}
