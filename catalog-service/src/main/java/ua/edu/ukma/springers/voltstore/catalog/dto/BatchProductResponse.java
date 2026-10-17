package ua.edu.ukma.springers.voltstore.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.math.BigDecimal;
import java.util.UUID;

// One element of the internal batch lookup (EP-CAT-09), used by Order Service for price snapshots and its checkout
// soft check. stockStatus is the stored read model of Inventory's stock (eventually consistent, no quantities).
@Data
@AllArgsConstructor
public class BatchProductResponse {
    private UUID productId;
    private String title;
    private BigDecimal basePrice;
    private ProductStatus status;
    private StockStatus stockStatus;

    public static BatchProductResponse from(Product product) {
        return new BatchProductResponse(product.getId(), product.getTitle(), product.getBasePrice(),
                product.getStatus(), product.getStockStatus());
    }
}
