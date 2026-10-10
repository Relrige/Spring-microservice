package ua.edu.ukma.springers.voltstore.catalog.services;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundForProductException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.repositories.CategoryRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;
import ua.edu.ukma.springers.voltstore.catalog.utils.ConstraintViolations;
import ua.edu.ukma.springers.voltstore.catalog.utils.RequestValidator;

import java.util.UUID;

// Products are never hard-deleted (deactivation is the only removal mechanism), so there is no delete method
@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final RequestValidator requestValidator;

    // EP-CAT-01. The request is already validated by @Valid in the controller.
    @Transactional
    public CreateProductResponse createProduct(ProductRequest request) {
        requireCategoryExists(request.getCategoryId());

        Product product = Product.createNew(request.getTitle().trim(), request.getDescription().trim(),
                request.getCategoryId(), request.getBasePrice());
        saveAndFlush(product);

        // ProductCreated will be emitted here, in this same transaction (tasks 11 and 19)
        return new CreateProductResponse(product.getId());
    }

    // EP-CAT-02. Order of checks follows the spec: 404, then 400, then 422.
    @Transactional
    public ProductResponse updateProduct(UUID id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        requestValidator.validate(request);
        requireCategoryExists(request.getCategoryId());

        // status and stockStatus are not editable here; @DynamicUpdate keeps them out of the UPDATE statement
        product.updateDetails(request.getTitle().trim(), request.getDescription().trim(),
                request.getCategoryId(), request.getBasePrice());
        saveAndFlush(product);
        return ProductResponse.from(product);
    }

    private void requireCategoryExists(UUID categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new CategoryNotFoundForProductException(categoryId);
        }
    }

    private void saveAndFlush(Product product) {
        try {
            productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            // Race condition: the category was deleted after the existence check; the foreign key is the real guard
            if (ConstraintViolations.isViolationOf(e, ConstraintViolations.PRODUCT_CATEGORY_FK)) {
                throw new CategoryNotFoundForProductException(product.getCategoryId());
            }
            throw e;
        }
    }
}
