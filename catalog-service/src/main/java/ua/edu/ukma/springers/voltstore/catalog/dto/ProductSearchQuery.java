package ua.edu.ukma.springers.voltstore.catalog.dto;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.utils.Paging;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Query parameters of GET /catalog/products (EP-CAT-04); all optional. Rules spanning several fields
 * ({@code minPrice} vs {@code maxPrice}) and the {@code sortBy} format are checked in the service.
 */
@Data
public class ProductSearchQuery {
    // Case-insensitive substring of title or description
    private String search;

    private UUID categoryId;

    @PositiveOrZero
    @Digits(integer = Product.PRICE_INTEGER_DIGITS, fraction = Product.PRICE_FRACTION_DIGITS)
    private BigDecimal minPrice;

    @PositiveOrZero
    @Digits(integer = Product.PRICE_INTEGER_DIGITS, fraction = Product.PRICE_FRACTION_DIGITS)
    private BigDecimal maxPrice;

    // "field" or "field,direction", e.g. "basePrice,desc"; field is title or basePrice
    @Parameter(description = "`title` or `basePrice`, optionally followed by `,asc` or `,desc`", example = "basePrice,desc")
    private String sortBy;

    // Applied for staff (Catalog Managers and Inventory Workers) only; ignored for every other caller
    @Parameter(description = "Applied for CATALOG_MANAGER and INVENTORY_WORKER only")
    private ProductStatus status;

    // page × size must fit the int row offset Spring Data passes to JPA
    @Parameter(description = "Zero-based page number, default 0")
    @PositiveOrZero
    @Max(Integer.MAX_VALUE / Paging.MAX_SIZE)
    private Integer page;

    @Parameter(description = "Page size, default 20, at most 100")
    @Positive
    @Max(Paging.MAX_SIZE)
    private Integer size;
}
