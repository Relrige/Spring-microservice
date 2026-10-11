package ua.edu.ukma.springers.voltstore.catalog.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.catalog.dto.BatchProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.PageResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductDetailsResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSearchQuery;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductStatusRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSummaryResponse;
import ua.edu.ukma.springers.voltstore.catalog.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.catalog.services.ProductService;

import java.util.List;
import java.util.UUID;

// No DELETE endpoint: products are never hard-deleted
@RestController
@RequestMapping("/catalog/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;

    // EP-CAT-04. The caller is the principal set by the trusted-header filter (null when anonymous or for
    // a service call); the controller never reads X-User-Role itself.
    @GetMapping
    public PageResponse<ProductSummaryResponse> browseProducts(@Valid @ModelAttribute ProductSearchQuery query,
                                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.browseProducts(query, user != null ? user.role() : null);
    }

    // EP-CAT-09 (internal). Spring MVC prefers this literal path over "/{id}" below. A malformed UUID in the
    // comma-separated list fails conversion → 400.
    @GetMapping("/batch-get")
    public List<BatchProductResponse> batchGetProducts(@RequestParam List<UUID> ids) {
        return productService.batchGetProducts(ids);
    }

    // EP-CAT-05
    @GetMapping("/{id}")
    public ProductDetailsResponse getProduct(@PathVariable UUID id) {
        return productService.getActiveProduct(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateProductResponse createProduct(@RequestBody @Valid ProductRequest request) {
        return productService.createProduct(request);
    }

    // No @Valid: the spec requires the 404 check before body validation, so the service validates
    @PutMapping("/{id}")
    public ProductResponse updateProduct(@PathVariable UUID id, @RequestBody ProductRequest request) {
        return productService.updateProduct(id, request);
    }

    // EP-CAT-03. No @Valid for the same reason as the update; the spec returns 200 OK with no body.
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.OK)
    public void changeStatus(@PathVariable UUID id, @RequestBody ProductStatusRequest request) {
        productService.changeStatus(id, request);
    }
}
