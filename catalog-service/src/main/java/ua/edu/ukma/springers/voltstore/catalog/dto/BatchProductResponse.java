package ua.edu.ukma.springers.voltstore.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;

import java.math.BigDecimal;
import java.util.UUID;

// One element of the internal batch lookup (EP-CAT-09), used by Order Service for price snapshots
@Data
@AllArgsConstructor
public class BatchProductResponse {
    private UUID productId;
    private String title;
    private BigDecimal basePrice;
    private ProductStatus status;

    public static BatchProductResponse from(Product product) {
        return new BatchProductResponse(product.getId(), product.getTitle(), product.getBasePrice(),
                product.getStatus());
    }
}
