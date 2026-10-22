package ua.edu.ukma.springers.voltstore.catalog.services;

import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import ua.edu.ukma.springers.voltstore.catalog.dto.BatchProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSearchQuery;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductStatusRequest;
import ua.edu.ukma.springers.voltstore.catalog.security.UserRole;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.events.ProductEvent;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.outbox.Outbox;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundForProductException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.InventoryUnavailableException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductActivationRejectedException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.RequestValidationException;
import ua.edu.ukma.springers.voltstore.catalog.repositories.CategoryRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;
import ua.edu.ukma.springers.voltstore.catalog.utils.RequestValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private Outbox outbox;

    @Mock
    private StockReadinessChecker stockReadinessChecker;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        RequestValidator requestValidator = new RequestValidator(
                Validation.buildDefaultValidatorFactory().getValidator());
        productService = new ProductService(productRepository, categoryRepository, requestValidator, outbox,
                stockReadinessChecker, TransactionOperations.withoutTransaction());
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
    void createProduct_appendsProductCreatedWithTheFullStateToTheOutbox() {
        UUID productId = UUID.randomUUID();
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);
        assignIdOnSave(productId);

        CreateProductResponse response = productService.createProduct(validRequest());

        assertThat(response.getProductId()).isEqualTo(productId);
        ProductEvent event = appendedEvent("ProductCreated", productId);
        assertThat(event.title()).isEqualTo("Phone");
        assertThat(event.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(event.basePrice()).isEqualByComparingTo("199.99");
        assertThat(event.status()).isEqualTo(ProductStatus.NOT_ACTIVE);
        assertThat(event.version()).isZero();
        assertThat(event.timestamp()).isNotNull();
    }

    // --- EP-CAT-EVT-01 / 02: stock status ---

    @Test
    void updateStockStatus_reportsWhetherTheProductExists() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(productRepository.updateStockStatus(known, StockStatus.IN_STOCK)).thenReturn(1);
        when(productRepository.updateStockStatus(unknown, StockStatus.IN_STOCK)).thenReturn(0);

        assertThat(productService.updateStockStatus(known, StockStatus.IN_STOCK)).isTrue();
        assertThat(productService.updateStockStatus(unknown, StockStatus.IN_STOCK)).isFalse();
    }

    @Test
    void createProduct_missingCategory_throwsAndDoesNotSave() {
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(false);

        assertThatThrownBy(() -> productService.createProduct(validRequest()))
                .isInstanceOf(CategoryNotFoundForProductException.class);
        verify(productRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void createProduct_foreignKeyViolation_mapsToCategoryNotFound() {
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);
        when(productRepository.saveAndFlush(any())).thenThrow(constraintViolation("fk_products_category"));

        assertThatThrownBy(() -> productService.createProduct(validRequest()))
                .isInstanceOf(CategoryNotFoundForProductException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void updateProduct_appendsProductUpdatedWithTheNewState() {
        UUID id = UUID.randomUUID();
        Product product = withId(existingProduct(), id);
        ReflectionTestUtils.setField(product, "version", 3L);
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);

        productService.updateProduct(id, request("New", "New description", CATEGORY_ID, "20"));

        ProductEvent event = appendedEvent("ProductUpdated", id);
        assertThat(event.title()).isEqualTo("New");
        assertThat(event.basePrice()).isEqualByComparingTo("20");
        assertThat(event.version()).isEqualTo(3L);
    }

    @Test
    void updateProduct_overlappingSave_propagatesAndStoresNoEvent() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(withId(existingProduct(), id)));
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);
        when(productRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, id));

        assertThatThrownBy(() -> productService.updateProduct(id, validRequest()))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verifyNoInteractions(outbox);
    }

    private ProductEvent appendedEvent(String eventType, UUID productId) {
        ArgumentCaptor<UUID> eventId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq("Product"), eq(productId), eq(eventType), eq("catalog.product-events"),
                eventId.capture(), payload.capture());
        ProductEvent event = (ProductEvent) payload.getValue();
        assertThat(event.productId()).isEqualTo(productId);
        assertThat(event.eventId()).isEqualTo(eventId.getValue());
        return event;
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
        Product product = withId(Product.createNew("Old", "Old description", CATEGORY_ID, new BigDecimal("10")), id);
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

    // --- EP-CAT-03: change status ---

    @Test
    void changeStatus_deactivate_setsNotActiveKeepsStockStatusAndAppendsProductUpdated() {
        UUID id = UUID.randomUUID();
        Product product = withId(activeProduct(), id);
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        productService.changeStatus(id, statusRequest(ProductStatus.NOT_ACTIVE));

        assertThat(product.getStatus()).isEqualTo(ProductStatus.NOT_ACTIVE);
        assertThat(product.getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
        // Flushed before the event is built, so the event carries the incremented version
        verify(productRepository).flush();
        assertThat(appendedEvent("ProductUpdated", id).status()).isEqualTo(ProductStatus.NOT_ACTIVE);
        // Deactivation never calls Inventory
        verifyNoInteractions(stockReadinessChecker);
    }

    @Test
    void changeStatus_activate_whenReady_setsActiveAndAppendsProductUpdated() {
        UUID id = UUID.randomUUID();
        Product product = withId(existingProduct(), id);
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(stockReadinessChecker.check(id)).thenReturn(StockReadiness.isReady());

        productService.changeStatus(id, statusRequest(ProductStatus.ACTIVE));

        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(appendedEvent("ProductUpdated", id).status()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void changeStatus_sameStatus_storesNoEvent() {
        UUID inactiveId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        when(productRepository.findById(inactiveId)).thenReturn(Optional.of(withId(existingProduct(), inactiveId)));
        when(productRepository.findById(activeId)).thenReturn(Optional.of(withId(activeProduct(), activeId)));
        when(stockReadinessChecker.check(activeId)).thenReturn(StockReadiness.isReady());

        productService.changeStatus(inactiveId, statusRequest(ProductStatus.NOT_ACTIVE));
        productService.changeStatus(activeId, statusRequest(ProductStatus.ACTIVE));

        verify(productRepository, never()).flush();
        verifyNoInteractions(outbox);
    }

    @Test
    void changeStatus_activate_whenNotReady_rejectsWithReasonAndKeepsStatus() {
        UUID id = UUID.randomUUID();
        Product product = existingProduct();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(stockReadinessChecker.check(id)).thenReturn(StockReadiness.notReady("packaging_data_missing"));

        assertThatThrownBy(() -> productService.changeStatus(id, statusRequest(ProductStatus.ACTIVE)))
                .isInstanceOfSatisfying(ProductActivationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo("packaging_data_missing"));
        assertThat(product.getStatus()).isEqualTo(ProductStatus.NOT_ACTIVE);
        verifyNoInteractions(outbox);
    }

    @Test
    void changeStatus_activate_whenInventoryUnavailable_propagatesAndKeepsStatus() {
        UUID id = UUID.randomUUID();
        Product product = existingProduct();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(stockReadinessChecker.check(id)).thenThrow(new InventoryUnavailableException("down", null));

        assertThatThrownBy(() -> productService.changeStatus(id, statusRequest(ProductStatus.ACTIVE)))
                .isInstanceOf(InventoryUnavailableException.class);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.NOT_ACTIVE);
        verifyNoInteractions(outbox);
    }

    @Test
    void changeStatus_activateAlreadyActiveProduct_reRunsTheCheck() {
        UUID id = UUID.randomUUID();
        Product product = activeProduct();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(stockReadinessChecker.check(id))
                .thenReturn(StockReadiness.isReady())
                .thenReturn(StockReadiness.notReady("stock_item_not_found"));

        productService.changeStatus(id, statusRequest(ProductStatus.ACTIVE));
        assertThatThrownBy(() -> productService.changeStatus(id, statusRequest(ProductStatus.ACTIVE)))
                .isInstanceOf(ProductActivationRejectedException.class);

        verify(stockReadinessChecker, times(2)).check(id);
        // A rejected repeat activation leaves the product as it was
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void changeStatus_invalidBody_doesNotCallInventory() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(existingProduct()));

        assertThatThrownBy(() -> productService.changeStatus(id, new ProductStatusRequest()))
                .isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(stockReadinessChecker);
    }

    @Test
    void changeStatus_notFound_throwsBeforeValidatingBody() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.changeStatus(id, new ProductStatusRequest()))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void changeStatus_missingStatus_throwsValidationError() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(existingProduct()));

        assertThatThrownBy(() -> productService.changeStatus(id, new ProductStatusRequest()))
                .isInstanceOfSatisfying(RequestValidationException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("status"));
    }

    // --- EP-CAT-05: product details ---

    @Test
    void getProduct_active_returnsFullViewToEveryCaller() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(activeProduct()));

        for (UserRole role : new UserRole[]{null, UserRole.CUSTOMER, UserRole.ADMIN, UserRole.CATALOG_MANAGER,
                UserRole.INVENTORY_WORKER}) {
            ProductResponse response = productService.getProduct(id, role);

            assertThat(response.getTitle()).isEqualTo("Old");
            assertThat(response.getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(response.getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
        }
    }

    @Test
    void getProduct_inactive_visibleToStaff() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(existingProduct()));

        for (UserRole role : new UserRole[]{UserRole.CATALOG_MANAGER, UserRole.INVENTORY_WORKER}) {
            assertThat(productService.getProduct(id, role).getStatus()).isEqualTo(ProductStatus.NOT_ACTIVE);
        }
    }

    @Test
    void getProduct_missing_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProduct(id, UserRole.CATALOG_MANAGER))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Product with ID " + id + " not found");
    }

    @Test
    void getProduct_inactiveForNonStaff_throwsTheSameNotFoundAsMissing() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(existingProduct()));

        for (UserRole role : new UserRole[]{null, UserRole.CUSTOMER, UserRole.ADMIN}) {
            assertThatThrownBy(() -> productService.getProduct(id, role))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessage("Product with ID " + id + " not found");
        }
    }

    // --- EP-CAT-04: browse ---

    @Test
    void visibleStatus_staffSeeEverythingOrTheRequestedStatus() {
        for (UserRole role : new UserRole[]{UserRole.CATALOG_MANAGER, UserRole.INVENTORY_WORKER}) {
            assertThat(ProductService.visibleStatus(null, role)).isNull();
            assertThat(ProductService.visibleStatus(ProductStatus.NOT_ACTIVE, role)).isEqualTo(ProductStatus.NOT_ACTIVE);
            assertThat(ProductService.visibleStatus(ProductStatus.ACTIVE, role)).isEqualTo(ProductStatus.ACTIVE);
        }
    }

    @Test
    void visibleStatus_everyoneElseSeesOnlyActive_evenWhenAskingForNotActive() {
        // null: anonymous, or an internal call without a user
        for (UserRole role : new UserRole[]{null, UserRole.CUSTOMER, UserRole.ADMIN}) {
            assertThat(ProductService.visibleStatus(null, role)).isEqualTo(ProductStatus.ACTIVE);
            assertThat(ProductService.visibleStatus(ProductStatus.NOT_ACTIVE, role)).isEqualTo(ProductStatus.ACTIVE);
        }
    }

    @Test
    void parseSort_defaultIsTitleThenId() {
        assertThat(ProductService.parseSort(null)).isEqualTo(Sort.by("title", "id"));
        assertThat(ProductService.parseSort(" ")).isEqualTo(Sort.by("title", "id"));
    }

    @Test
    void parseSort_acceptsWhitelistedFieldWithOptionalDirection() {
        assertThat(ProductService.parseSort("basePrice"))
                .isEqualTo(Sort.by(Sort.Direction.ASC, "basePrice").and(Sort.by("id")));
        assertThat(ProductService.parseSort("basePrice,DESC"))
                .isEqualTo(Sort.by(Sort.Direction.DESC, "basePrice").and(Sort.by("id")));
        assertThat(ProductService.parseSort("title,asc"))
                .isEqualTo(Sort.by(Sort.Direction.ASC, "title").and(Sort.by("id")));
    }

    @Test
    void parseSort_rejectsUnknownFieldsAndDirections() {
        for (String sortBy : new String[]{"status", "id", "description,asc", "title,up", "title,asc,desc", ","}) {
            assertThatThrownBy(() -> ProductService.parseSort(sortBy))
                    .as(sortBy)
                    .isInstanceOfSatisfying(RequestValidationException.class,
                            e -> assertThat(e.getErrors()).containsOnlyKeys("sortBy"));
        }
    }

    @Test
    void browseProducts_minPriceAboveMaxPrice_throwsWithoutQuerying() {
        ProductSearchQuery query = new ProductSearchQuery();
        query.setMinPrice(new BigDecimal("100"));
        query.setMaxPrice(new BigDecimal("50"));

        assertThatThrownBy(() -> productService.browseProducts(query, null))
                .isInstanceOfSatisfying(RequestValidationException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("minPrice"));
        verifyNoInteractions(productRepository);
    }

    // --- EP-CAT-09: batch get ---

    @Test
    void batchGetProducts_returnsFoundProductsOnceInRequestOrder() {
        Product first = withId(existingProduct(), UUID.randomUUID());
        Product second = withId(activeProduct(), UUID.randomUUID());
        UUID unknown = UUID.randomUUID();
        when(productRepository.findAllById(any())).thenReturn(List.of(second, first));

        List<BatchProductResponse> result = productService.batchGetProducts(
                List.of(first.getId(), unknown, second.getId(), first.getId()));

        assertThat(result).extracting(BatchProductResponse::getProductId).containsExactly(first.getId(), second.getId());
        assertThat(result).extracting(BatchProductResponse::getStatus)
                .containsExactly(ProductStatus.NOT_ACTIVE, ProductStatus.ACTIVE);
    }

    @Test
    void batchGetProducts_queriesEachIdOnce() {
        UUID id = UUID.randomUUID();
        when(productRepository.findAllById(any())).thenReturn(List.of());

        assertThat(productService.batchGetProducts(List.of(id, id))).isEmpty();

        ArgumentCaptor<Iterable<UUID>> ids = ArgumentCaptor.captor();
        verify(productRepository).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactly(id);
    }

    @Test
    void batchGetProducts_emptyOrNullElement_throwsValidationError() {
        List<UUID> withNull = new ArrayList<>();
        withNull.add(UUID.randomUUID());
        withNull.add(null);

        for (List<UUID> ids : List.of(List.<UUID>of(), withNull)) {
            assertThatThrownBy(() -> productService.batchGetProducts(ids))
                    .isInstanceOfSatisfying(RequestValidationException.class,
                            e -> assertThat(e.getErrors()).containsOnlyKeys("ids"));
        }
        verifyNoInteractions(productRepository);
    }

    @Test
    void batchGetProducts_moreThanTheLimit_throwsValidationError() {
        List<UUID> ids = Stream.generate(UUID::randomUUID).limit(ProductService.BATCH_GET_MAX_IDS + 1).toList();

        assertThatThrownBy(() -> productService.batchGetProducts(ids))
                .isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(productRepository);
    }

    @Test
    void batchGetProducts_exactlyTheLimit_isAccepted() {
        List<UUID> ids = Stream.generate(UUID::randomUUID).limit(ProductService.BATCH_GET_MAX_IDS).toList();
        when(productRepository.findAllById(any())).thenReturn(List.of());

        assertThat(productService.batchGetProducts(ids)).isEmpty();
    }

    private static Product activeProduct() {
        Product product = existingProduct();
        product.activate();
        ReflectionTestUtils.setField(product, "stockStatus", StockStatus.IN_STOCK);
        return product;
    }

    private static Product withId(Product product, UUID id) {
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private static ProductStatusRequest statusRequest(ProductStatus status) {
        ProductStatusRequest request = new ProductStatusRequest();
        request.setStatus(status);
        return request;
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
