package ua.edu.ukma.springers.voltstore.catalog.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.catalog.domain.entity.Product;
import ua.edu.ukma.springers.voltstore.catalog.services.ProductService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<Product> create(@RequestBody Product product) {
        Product created = productService.createProduct(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<Product>> getAll() {
        return ResponseEntity.ok(productService.getAllActiveProducts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getById(@PathVariable UUID id,
                                           @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        log.info("Received request for product id={}, correlationId={}", id, correlationId);
        return ResponseEntity.ok(productService.getProductById(id));
    }

    @GetMapping("/{id}/slow")
    public ResponseEntity<Product> getByIdSlow(@PathVariable UUID id,
                                               @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        log.warn("Simulating slow response (3500 ms) for product id={}, correlationId={}", id, correlationId);
        try {
            Thread.sleep(3500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ResponseEntity.ok(productService.getProductById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable UUID id, @RequestBody Product product) {
        return ResponseEntity.ok(productService.updateProduct(id, product));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}