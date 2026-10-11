package ua.edu.ukma.springers.voltstore.catalog.services;

import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.events.ProductCreatedEvent;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundForProductException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.RequestValidationException;
import ua.edu.ukma.springers.voltstore.catalog.repositories.CategoryRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;
import ua.edu.ukma.springers.voltstore.catalog.utils.RequestValidator;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ua.edu.ukma.springers.voltstore.catalog.services.CategoryServiceTest.constraintViolation;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    private static final UUID CATEGORY_ID = UUID.randomUUID();

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        RequestValidator requestValidator = new RequestValidator(
                Validation.buildDefaultValidatorFactory().getValidator());
        productService = new ProductService(productRepository, categoryRepository, requestValidator, eventPublisher);
    }

    // --- create ---

    @Test
    void createProduct_persistsWithDefaultsAndTrimmedText() {
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);
        assignIdOnSave(UUID.randomUUID());

        productService.createProduct(request(" Phone ", " A phone ", CATEGORY_ID, "199.9"));

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).saveAndFlush(saved.capture());
        Product product = saved.getValue();
        assertThat(product.getTitle()).isEqualTo("Phone");
        assertThat(product.getDescription()).isEqualTo("A phone");
        assertThat(product.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(product.getBasePrice()).isEqualTo(new BigDecimal("199.90"));
        assertThat(product.getStatus()).isEqualTo(ProductStatus.NOT_ACTIVE);
        assertThat(product.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
    }

    @Test
    void createProduct_publishesProductCreatedForTheSavedProduct() {
        UUID productId = UUID.randomUUID();
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);
        assignIdOnSave(productId);

        CreateProductResponse response = productService.createProduct(validRequest());

        assertThat(response.getProductId()).isEqualTo(productId);
        ArgumentCaptor<ProductCreatedEvent> published = ArgumentCaptor.forClass(ProductCreatedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().productId()).isEqualTo(productId);
        assertThat(published.getValue().eventId()).isNotNull();
        assertThat(published.getValue().timestamp()).isNotNull();
    }

    @Test
    void createProduct_missingCategory_throwsAndDoesNotSave() {
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(false);

        assertThatThrownBy(() -> productService.createProduct(validRequest()))
                .isInstanceOf(CategoryNotFoundForProductException.class);
        verify(productRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void createProduct_foreignKeyViolation_mapsToCategoryNotFound() {
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);
        when(productRepository.saveAndFlush(any())).thenThrow(constraintViolation("fk_products_category"));

        assertThatThrownBy(() -> productService.createProduct(validRequest()))
                .isInstanceOf(CategoryNotFoundForProductException.class);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void updateProduct_doesNotPublishEvents() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(existingProduct()));
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);

        productService.updateProduct(id, validRequest());

        verifyNoInteractions(eventPublisher);
    }

    // Simulates Hibernate generating the ID when the entity is persisted
    private void assignIdOnSave(UUID id) {
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            ReflectionTestUtils.setField(product, "id", id);
            return product;
        });
    }

    // --- update ---

    @Test
    void updateProduct_updatesEditableFieldsOnly() {
        UUID id = UUID.randomUUID();
        UUID newCategoryId = UUID.randomUUID();
        Product product = Product.createNew("Old", "Old description", CATEGORY_ID, new BigDecimal("10"));
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(categoryRepository.existsById(newCategoryId)).thenReturn(true);

        ProductResponse response = productService.updateProduct(id,
                request("New", "New description", newCategoryId, "20.50"));

        assertThat(product.getTitle()).isEqualTo("New");
        assertThat(product.getDescription()).isEqualTo("New description");
        assertThat(product.getCategoryId()).isEqualTo(newCategoryId);
        assertThat(product.getBasePrice()).isEqualTo(new BigDecimal("20.50"));
        assertThat(product.getStatus()).isEqualTo(ProductStatus.NOT_ACTIVE);
        assertThat(product.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(response.getTitle()).isEqualTo("New");
        verify(productRepository).saveAndFlush(product);
    }

    @Test
    void updateProduct_notFound_throwsBeforeValidatingBody() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.updateProduct(id, new ProductRequest()))
                .isInstanceOf(ProductNotFoundException.class);
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void updateProduct_invalidBody_throwsValidationErrorBeforeCategoryCheck() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(existingProduct()));

        assertThatThrownBy(() -> productService.updateProduct(id, request("", "", null, "0")))
                .isInstanceOfSatisfying(RequestValidationException.class,
                        e -> assertThat(e.getErrors()).containsKeys("title", "description", "categoryId", "basePrice"));
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void updateProduct_missingCategory_throwsAndKeepsProductUnchanged() {
        UUID id = UUID.randomUUID();
        Product product = existingProduct();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(false);

        assertThatThrownBy(() -> productService.updateProduct(id, request("New", "New", CATEGORY_ID, "5")))
                .isInstanceOf(CategoryNotFoundForProductException.class);
        assertThat(product.getTitle()).isEqualTo("Old");
        verify(productRepository, never()).saveAndFlush(any());
    }

    private static Product existingProduct() {
        return Product.createNew("Old", "Old description", CATEGORY_ID, new BigDecimal("10"));
    }

    private static ProductRequest validRequest() {
        return request("Phone", "A phone", CATEGORY_ID, "199.99");
    }

    private static ProductRequest request(String title, String description, UUID categoryId, String basePrice) {
        ProductRequest request = new ProductRequest();
        request.setTitle(title);
        request.setDescription(description);
        request.setCategoryId(categoryId);
        request.setBasePrice(new BigDecimal(basePrice));
        return request;
    }
}
