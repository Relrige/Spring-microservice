package ua.edu.ukma.springers.voltstore.catalog.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.events.ProductCreatedEvent;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundForProductException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.repositories.CategoryRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;
import ua.edu.ukma.springers.voltstore.catalog.utils.ConstraintViolations;
import ua.edu.ukma.springers.voltstore.catalog.utils.RequestValidator;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final RequestValidator requestValidator;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CreateProductResponse createProduct(ProductRequest request) {
        requireCategoryExists(request.getCategoryId());

        Product product = Product.createNew(request.getTitle().trim(), request.getDescription().trim(),
                request.getCategoryId(), request.getBasePrice());
        saveAndFlush(product);

        eventPublisher.publishEvent(ProductCreatedEvent.forProduct(product.getId()));
        return new CreateProductResponse(product.getId());
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        requestValidator.validate(request);
        requireCategoryExists(request.getCategoryId());

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
            if (ConstraintViolations.isViolationOf(e, ConstraintViolations.PRODUCT_CATEGORY_FK)) {
                throw new CategoryNotFoundForProductException(product.getCategoryId());
            }
            throw e;
        }
    }
}
