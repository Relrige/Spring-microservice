package ua.edu.ukma.springers.voltstore.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.math.BigDecimal;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// Customer view of one product (EP-CAT-05). No status: only ACTIVE products are ever returned here.
@Data
@AllArgsConstructor
public class ProductDetailsResponse {
    @Schema(requiredMode = REQUIRED)
    private UUID productId;
    @Schema(requiredMode = REQUIRED)
    private String title;
    @Schema(requiredMode = REQUIRED)
    private String description;
    @Schema(requiredMode = REQUIRED)
    private UUID categoryId;
    @Schema(requiredMode = REQUIRED)
    private BigDecimal basePrice;
    @Schema(requiredMode = REQUIRED)
    private StockStatus stockStatus;

    public static ProductDetailsResponse from(Product product) {
        return new ProductDetailsResponse(product.getId(), product.getTitle(), product.getDescription(),
                product.getCategoryId(), product.getBasePrice(), product.getStockStatus());
    }
}
