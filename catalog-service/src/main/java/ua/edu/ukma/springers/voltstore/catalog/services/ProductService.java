package ua.edu.ukma.springers.voltstore.catalog.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.catalog.domain.entity.Product;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public Product createProduct(Product product) {
        if (productRepository.existsBySku(product.getSku())) {
            throw new IllegalArgumentException("SKU already exists: " + product.getSku());
        }
        if (product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be greater than zero");
        }
        return productRepository.save(product);
    }

    public List<Product> getAllActiveProducts() {
        return productRepository.findAllByDeletedFalse();
    }

    public Product getProductById(UUID id) {
        return productRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("Product not found or deleted"));
    }

    @Transactional
    public Product updateProduct(UUID id, Product updatedData) {
        Product existingProduct = getProductById(id);

        if (updatedData.getPrice() != null && updatedData.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be greater than zero");
        }

        existingProduct.setTitle(updatedData.getTitle() != null ? updatedData.getTitle() : existingProduct.getTitle());
        existingProduct.setDescription(updatedData.getDescription() != null ? updatedData.getDescription() : existingProduct.getDescription());
        existingProduct.setCategory(updatedData.getCategory() != null ? updatedData.getCategory() : existingProduct.getCategory());
        existingProduct.setPrice(updatedData.getPrice() != null ? updatedData.getPrice() : existingProduct.getPrice());

        return productRepository.save(existingProduct);
    }

    @Transactional
    public void deleteProduct(UUID id) {
        Product product = getProductById(id);
        product.setDeleted(true);
        productRepository.save(product);
    }
}