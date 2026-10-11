package ua.edu.ukma.springers.voltstore.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.math.BigDecimal;
import java.util.UUID;

// Full (manager) view of a product
@Data
@AllArgsConstructor
public class ProductResponse {
    private UUID productId;
    private String title;
    private String description;
    private UUID categoryId;
    private BigDecimal basePrice;
    private ProductStatus status;
    private StockStatus stockStatus;

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getTitle(), product.getDescription(),
                product.getCategoryId(), product.getBasePrice(), product.getStatus(), product.getStockStatus());
    }
}
