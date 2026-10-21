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

// One element of the internal batch lookup (EP-CAT-09), used by Order Service for price snapshots and its checkout
// soft check. stockStatus is the stored read model of Inventory's stock (eventually consistent, no quantities).
@Data
@AllArgsConstructor
public class BatchProductResponse {
    @Schema(requiredMode = REQUIRED)
    private UUID productId;
    @Schema(requiredMode = REQUIRED)
    private String title;
    @Schema(requiredMode = REQUIRED)
    private BigDecimal basePrice;
    @Schema(requiredMode = REQUIRED)
    private ProductStatus status;
    @Schema(requiredMode = REQUIRED)
    private StockStatus stockStatus;

    public static BatchProductResponse from(Product product) {
        return new BatchProductResponse(product.getId(), product.getTitle(), product.getBasePrice(),
                product.getStatus(), product.getStockStatus());
    }
}
