package ua.edu.ukma.springers.voltstore.inventory.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.inventory.dto.AdjustStockRequest;
import ua.edu.ukma.springers.voltstore.inventory.dto.AdjustStockResponse;
import ua.edu.ukma.springers.voltstore.inventory.dto.PackagingResponse;
import ua.edu.ukma.springers.voltstore.inventory.dto.SetPackagingRequest;
import ua.edu.ukma.springers.voltstore.inventory.dto.StockBalanceResponse;
import ua.edu.ukma.springers.voltstore.inventory.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.inventory.openapi.InternalApi;
import ua.edu.ukma.springers.voltstore.inventory.openapi.PackagingNotConfiguredProblem;
import ua.edu.ukma.springers.voltstore.inventory.openapi.Problem;
import ua.edu.ukma.springers.voltstore.inventory.openapi.ValidationProblem;
import ua.edu.ukma.springers.voltstore.inventory.paging.PageResponse;
import ua.edu.ukma.springers.voltstore.inventory.paging.PageableParser;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
import static ua.edu.ukma.springers.voltstore.inventory.openapi.OpenApiConfig.BEARER_AUTH;

@Tag(name = "Stock")
@RestController
@RequestMapping("/inventory/stock")
@RequiredArgsConstructor
public class StockController {
    // Public sortBy names mapped to entity properties; productId is the tiebreaker (and the default order). Rows
    // without a copied title come last in both directions.
    private static final PageableParser PAGEABLE_PARSER = new PageableParser(Map.of(
            "productId", "productId",
            "title", "title",
            "availableQuantity", "availableQuantity",
            "reservedQuantity", "reservedQuantity"), "productId", Set.of("title"));

    private final StockService stockService;

    @Operation(summary = "List stock balances (EP-INV-01)", description = "Role: INVENTORY_WORKER.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "One page of stock balances")
    @ApiResponse(responseCode = "400", description = "Invalid query parameters",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not an INVENTORY_WORKER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @GetMapping
    public PageResponse<StockBalanceResponse> listStock(
            @Parameter(description = "Only items whose packaging is (or is not) configured")
            @RequestParam(required = false) Boolean packagingConfigured,
            @Parameter(description = "Case-insensitive part of the product title; `%` and `_` match literally. "
                    + "Blank means no filter; rows without a copied title never match")
            @RequestParam(required = false) String search,
            @Parameter(description = "Only items whose product has this Catalog status")
            @RequestParam(required = false) ProductStatus productStatus,
            @Parameter(description = "Zero-based page number")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most " + PageableParser.MAX_SIZE)
            @RequestParam(defaultValue = "" + PageableParser.DEFAULT_SIZE) int size,
            @Parameter(description = "`productId`, `title`, `availableQuantity` or `reservedQuantity`, optionally "
                    + "followed by `,asc` or `,desc`; default `productId`. Rows without a title come last",
                    example = "availableQuantity,desc")
            @RequestParam(required = false) String sortBy) {
        return PageResponse.from(
                stockService.listStock(packagingConfigured, search, productStatus,
                        PAGEABLE_PARSER.parse(page, size, sortBy)),
                StockBalanceResponse::from);
    }

    @Operation(summary = "Restock or write off (EP-INV-02)",
            description = "Role: INVENTORY_WORKER. A positive `delta` restocks, a negative one writes off. The first "
                    + "adjustment of a product creates its stock item after Catalog confirms the product exists.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "Adjusted")
    @ApiResponse(responseCode = "400", description = "Missing, zero or non-integer `delta`",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not an INVENTORY_WORKER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "The product does not exist in the catalog",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "A write-off would make the available stock negative",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "503", description = "Catalog cannot be reached; nothing changed",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping("/{productId}/adjust")
    public AdjustStockResponse adjustStock(@PathVariable UUID productId,
                                           @Valid @RequestBody AdjustStockRequest request) {
        return new AdjustStockResponse(stockService.adjustStock(productId, request.delta()));
    }

    /**
     * Internal (Catalog): 204 if the product is ready for activation; otherwise 404 (no StockItem) or
     * 422 (packaging not configured) from the exception handler.
     */
    @InternalApi
    @Operation(summary = "Check readiness for activation (EP-INV-04)",
            description = "Ready means: a stock item exists and its packaging is configured.")
    @ApiResponse(responseCode = "204", description = "Ready")
    @ApiResponse(responseCode = "401", description = "Missing or invalid internal token",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No stock item for this product",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "Packaging not configured",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = PackagingNotConfiguredProblem.class)))
    @GetMapping("/{productId}/readiness")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void checkReadiness(@PathVariable UUID productId) {
        stockService.checkReadiness(productId);
    }

    /**
     * Internal (Delivery): the packaging values; 404 (no StockItem) or 422 (packaging not configured) otherwise.
     */
    @InternalApi
    @Operation(summary = "Get packaging dimensions (EP-INV-05)")
    @ApiResponse(responseCode = "200", description = "The packaging values")
    @ApiResponse(responseCode = "401", description = "Missing or invalid internal token",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No stock item for this product",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "Packaging not configured",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = PackagingNotConfiguredProblem.class)))
    @GetMapping("/{productId}/packaging")
    public PackagingResponse getPackaging(@PathVariable UUID productId) {
        return PackagingResponse.from(stockService.getPackaging(productId));
    }

    @Operation(summary = "Set packaging dimensions (EP-INV-03)",
            description = "Role: INVENTORY_WORKER. Creates the stock item (with zero stock) if it does not exist yet, "
                    + "after Catalog confirms the product exists.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "204", description = "Saved")
    @ApiResponse(responseCode = "400", description = "A value is missing, not positive or not an integer",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not an INVENTORY_WORKER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "The product does not exist in the catalog",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "503", description = "Catalog cannot be reached; nothing changed",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PutMapping("/{productId}/packaging")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPackaging(@PathVariable UUID productId, @Valid @RequestBody SetPackagingRequest request) {
        stockService.setPackaging(productId, request.grossWeightGrams(), request.lengthCm(),
                request.widthCm(), request.heightCm());
    }
}
