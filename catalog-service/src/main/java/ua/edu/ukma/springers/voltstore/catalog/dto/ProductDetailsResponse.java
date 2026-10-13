package ua.edu.ukma.springers.voltstore.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.math.BigDecimal;
import java.util.UUID;

// Customer view of one product (EP-CAT-05). No status: only ACTIVE products are ever returned here.
@Data
@AllArgsConstructor
public class ProductDetailsResponse {
    private UUID productId;
    private String title;
    private String description;
    private UUID categoryId;
    private BigDecimal basePrice;
    private StockStatus stockStatus;

    public static ProductDetailsResponse from(Product product) {
        return new ProductDetailsResponse(product.getId(), product.getTitle(), product.getDescription(),
                product.getCategoryId(), product.getBasePrice(), product.getStockStatus());
    }
}
