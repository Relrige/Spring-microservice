package ua.edu.ukma.springers.voltstore.catalog.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.catalog.dto.BatchProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.PageResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSearchQuery;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductStatusRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSummaryResponse;
import ua.edu.ukma.springers.voltstore.catalog.openapi.ActivationRejectedProblem;
import ua.edu.ukma.springers.voltstore.catalog.openapi.InternalApi;
import ua.edu.ukma.springers.voltstore.catalog.openapi.OptionalAuthentication;
import ua.edu.ukma.springers.voltstore.catalog.openapi.Problem;
import ua.edu.ukma.springers.voltstore.catalog.openapi.ValidationProblem;
import ua.edu.ukma.springers.voltstore.catalog.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.catalog.services.ProductService;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
import static ua.edu.ukma.springers.voltstore.catalog.openapi.OpenApiConfig.BEARER_AUTH;

// No DELETE endpoint: products are never hard-deleted
@Tag(name = "Products")
@RestController
@RequestMapping("/catalog/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;

    // EP-CAT-04. The caller is the principal set by the trusted-header filter (null when anonymous or for
    // a service call); the controller never reads X-User-Role itself.
    @Operation(summary = "Search products (EP-CAT-04)",
            description = "Role: none, or CATALOG_MANAGER or INVENTORY_WORKER. Anonymous callers and every other role "
                    + "see only ACTIVE products, and `status` is ignored for them. A CATALOG_MANAGER or "
                    + "INVENTORY_WORKER sees ACTIVE and NOT_ACTIVE products and may filter by `status`.")
    @OptionalAuthentication
    @ApiResponse(responseCode = "200", description = "One page of products")
    @ApiResponse(responseCode = "400", description = "Invalid query parameters, or minPrice greater than maxPrice",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @GetMapping
    public PageResponse<ProductSummaryResponse> browseProducts(@Valid @ModelAttribute @ParameterObject ProductSearchQuery query,
                                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.browseProducts(query, user != null ? user.role() : null);
    }

    // EP-CAT-09 (internal). Spring MVC prefers this literal path over "/{id}" below. A malformed UUID in the
    // comma-separated list fails conversion → 400.
    @InternalApi
    @Operation(summary = "Look up several products by ID (EP-CAT-09)",
            description = "Comma-separated `ids`, at most 100 distinct. Products of any status; unknown IDs are "
                    + "left out of the answer, and each product appears once.")
    @ApiResponse(responseCode = "200", description = "The products found")
    @ApiResponse(responseCode = "400", description = "Missing, empty or malformed `ids`, or more than 100",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid internal token",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @GetMapping("/batch-get")
    public List<BatchProductResponse> batchGetProducts(@RequestParam List<UUID> ids) {
        return productService.batchGetProducts(ids);
    }

    // EP-CAT-05. Same caller handling and visibility rule as EP-CAT-04.
    @Operation(summary = "Get a product (EP-CAT-05)",
            description = "Role: none, or CATALOG_MANAGER or INVENTORY_WORKER. These two roles see products of any "
                    + "status; for every other caller a NOT_ACTIVE product answers the same 404 as a missing one.")
    @OptionalAuthentication
    @ApiResponse(responseCode = "200", description = "The product")
    @ApiResponse(responseCode = "404", description = "No product with this ID that the caller may see",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.getProduct(id, user != null ? user.role() : null);
    }

    @Operation(summary = "Create a product (EP-CAT-01)",
            description = "Role: CATALOG_MANAGER. The product is created NOT_ACTIVE.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Invalid fields",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CATALOG_MANAGER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "The category does not exist",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateProductResponse createProduct(@RequestBody @Valid ProductRequest request) {
        return productService.createProduct(request);
    }

    // No @Valid: the spec requires the 404 check before body validation, so the service validates
    @Operation(summary = "Update a product (EP-CAT-02)",
            description = "Role: CATALOG_MANAGER. Status and stock status are not editable here.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The updated product")
    @ApiResponse(responseCode = "400", description = "Invalid fields",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CATALOG_MANAGER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No product with this ID",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "The category does not exist",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PutMapping("/{id}")
    public ProductResponse updateProduct(@PathVariable UUID id, @RequestBody ProductRequest request) {
        return productService.updateProduct(id, request);
    }

    // EP-CAT-03. No @Valid for the same reason as the update; the spec returns 200 OK with no body.
    @Operation(summary = "Activate or deactivate a product (EP-CAT-03)",
            description = "Role: CATALOG_MANAGER. Activation first asks Inventory whether the product is ready "
                    + "(stock item and packaging data exist).",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "Status set")
    @ApiResponse(responseCode = "400", description = "Missing or invalid status",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CATALOG_MANAGER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No product with this ID",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "Inventory says the product is not ready for activation",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ActivationRejectedProblem.class)))
    @ApiResponse(responseCode = "503", description = "Inventory cannot be reached; try again later",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.OK)
    public void changeStatus(@PathVariable UUID id, @RequestBody ProductStatusRequest request) {
        productService.changeStatus(id, request);
    }
}
