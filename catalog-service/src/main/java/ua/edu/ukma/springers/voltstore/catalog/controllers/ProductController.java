package ua.edu.ukma.springers.voltstore.catalog.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.catalog.domain.entity.Product;
import ua.edu.ukma.springers.voltstore.catalog.services.ProductService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/batch")
    public ResponseEntity<List<Product>> getProductsBatch(@RequestParam List<UUID> ids) {
        return ResponseEntity.ok(productService.getProductsByIds(ids));
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
    public ResponseEntity<Product> getById(@PathVariable UUID id) {
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