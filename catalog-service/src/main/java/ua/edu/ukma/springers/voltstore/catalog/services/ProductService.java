package ua.edu.ukma.springers.voltstore.catalog.services;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.catalog.dto.BatchProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.PageResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductDetailsResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSearchQuery;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductStatusRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSummaryResponse;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.events.ProductCreatedEvent;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundForProductException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.RequestValidationException;
import ua.edu.ukma.springers.voltstore.catalog.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.catalog.repositories.CategoryRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductSpecifications;
import ua.edu.ukma.springers.voltstore.catalog.security.UserRole;
import ua.edu.ukma.springers.voltstore.catalog.utils.ConstraintViolations;
import ua.edu.ukma.springers.voltstore.catalog.utils.Paging;
import ua.edu.ukma.springers.voltstore.catalog.utils.RequestValidator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {
    // EP-CAT-09: an order never has this many distinct products; bounds the size of the IN (...) query
    public static final int BATCH_GET_MAX_IDS = 100;

    // sortBy values accepted by EP-CAT-04, mapped to entity properties. Never sort by an arbitrary property name.
    private static final Map<String, String> SORTABLE_FIELDS = Map.of("title", "title", "basePrice", "basePrice");
    private static final String SORT_BY_RULE = "must be title or basePrice, optionally followed by ,asc or ,desc";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final RequestValidator requestValidator;
    private final Outbox outbox;

    @Transactional
    public CreateProductResponse createProduct(ProductRequest request) {
        requireCategoryExists(request.getCategoryId());

        Product product = Product.createNew(request.getTitle().trim(), request.getDescription().trim(),
                request.getCategoryId(), request.getBasePrice());
        saveAndFlush(product);

        // Same transaction as the product: the event is stored if and only if the product is (outbox)
        ProductCreatedEvent event = ProductCreatedEvent.forProduct(product.getId());
        outbox.append("Product", product.getId(), ProductCreatedEvent.EVENT_TYPE, KafkaTopics.PRODUCT_EVENTS,
                event.eventId(), event);
        return new CreateProductResponse(product.getId());
    }

    /**
     * EP-CAT-EVT-01 / EP-CAT-EVT-02: applies a stock event to the read-model. Setting a value is idempotent,
     * and the status of the product ({@code ACTIVE} or not) does not matter.
     *
     * @return false if no product has this ID
     */
    @Transactional
    public boolean updateStockStatus(UUID productId, StockStatus stockStatus) {
        return productRepository.updateStockStatus(productId, stockStatus) > 0;
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, ProductRequest request) {
        Product product = findProduct(id);
        requestValidator.validate(request);
        requireCategoryExists(request.getCategoryId());

        product.updateDetails(request.getTitle().trim(), request.getDescription().trim(),
                request.getCategoryId(), request.getBasePrice());
        saveAndFlush(product);
        return ProductResponse.from(product);
    }

    /**
     * EP-CAT-03. Order of checks follows the spec: 404, then 400. Only {@code status} changes (thanks to
     * {@code @DynamicUpdate}, {@code stockStatus} is never written), and no event is published. Setting the
     * current status again is an idempotent success. A deactivation racing with an activation is last-write-wins.
     */
    @Transactional
    public void changeStatus(UUID id, ProductStatusRequest request) {
        Product product = findProduct(id);
        requestValidator.validate(request);

        switch (request.getStatus()) {
            case ACTIVE -> activate(product);
            case NOT_ACTIVE -> product.deactivate();
        }
    }

    // EP-CAT-05: the customer view. An inactive product gets exactly the same 404 as a missing one,
    // so the existence of unpublished products does not leak (for every caller, managers included).
    @Transactional(readOnly = true)
    public ProductDetailsResponse getActiveProduct(UUID id) {
        return productRepository.findById(id)
                .filter(product -> product.getStatus() == ProductStatus.ACTIVE)
                .map(ProductDetailsResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    /**
     * EP-CAT-04. Catalog Managers see every product and may filter by {@code status}; every other caller
     * (anonymous included, {@code callerRole == null}) sees ACTIVE products only and the {@code status}
     * parameter is ignored. {@code stockStatus} comes from the stored read-model column; Inventory is not called.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryResponse> browseProducts(ProductSearchQuery query, @Nullable UserRole callerRole) {
        if (query.getMinPrice() != null && query.getMaxPrice() != null
                && query.getMinPrice().compareTo(query.getMaxPrice()) > 0) {
            throw new RequestValidationException(Map.of("minPrice", List.of("must not be greater than maxPrice")));
        }
        Sort sort = parseSort(query.getSortBy());

        List<Specification<Product>> filters = new ArrayList<>();
        ProductStatus status = visibleStatus(query.getStatus(), callerRole);
        if (status != null) {
            filters.add(ProductSpecifications.hasStatus(status));
        }
        if (query.getCategoryId() != null) {
            filters.add(ProductSpecifications.inCategory(query.getCategoryId()));
        }
        if (query.getMinPrice() != null) {
            filters.add(ProductSpecifications.priceAtLeast(query.getMinPrice()));
        }
        if (query.getMaxPrice() != null) {
            filters.add(ProductSpecifications.priceAtMost(query.getMaxPrice()));
        }
        if (query.getSearch() != null && !query.getSearch().isBlank()) {
            filters.add(ProductSpecifications.titleOrDescriptionContains(query.getSearch().trim()));
        }

        var page = productRepository.findAll(Specification.allOf(filters),
                Paging.pageRequest(query.getPage(), query.getSize(), sort));
        return PageResponse.from(page, ProductSummaryResponse::from);
    }

    /**
     * EP-CAT-09 (internal). Returns the products that exist, whatever their status; unknown IDs are simply
     * absent (the spec's {@code notFound} list was dropped, as agreed with the team). Results follow the
     * order of the request and contain each product once.
     */
    @Transactional(readOnly = true)
    public List<BatchProductResponse> batchGetProducts(@Nullable Collection<UUID> ids) {
        // A list such as "?ids=a,,b" binds an empty element as null
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(Objects::isNull)) {
            throw new RequestValidationException(Map.of("ids", List.of("must be a non-empty list of UUIDs")));
        }
        Set<UUID> distinctIds = new LinkedHashSet<>(ids);
        if (distinctIds.size() > BATCH_GET_MAX_IDS) {
            throw new RequestValidationException(Map.of("ids",
                    List.of("must contain at most " + BATCH_GET_MAX_IDS + " IDs")));
        }

        Map<UUID, Product> found = productRepository.findAllById(distinctIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return distinctIds.stream()
                .map(found::get)
                .filter(Objects::nonNull)
                .map(BatchProductResponse::from)
                .toList();
    }

    // TEMPORARY deviation from EP-CAT-03 until task 21: the spec requires an Inventory readiness check
    // (GET /inventory/stock/{id}/readiness) before activation. Until then any product can be activated.
    private void activate(Product product) {
        product.activate();
    }

    /**
     * Role-based visibility of the product listing (business logic, not a security rule): the status filter to
     * apply, or null for no status filter. Only a Catalog Manager may see NOT_ACTIVE products.
     */
    static @Nullable ProductStatus visibleStatus(@Nullable ProductStatus requested, @Nullable UserRole callerRole) {
        return callerRole == UserRole.CATALOG_MANAGER ? requested : ProductStatus.ACTIVE;
    }

    /**
     * {@code sortBy} format: {@code field} or {@code field,direction} with field {@code title} or
     * {@code basePrice} and direction {@code asc} (default) or {@code desc}. Without it the listing is sorted
     * by title. {@code id} is always the last key, so equal values never make paging nondeterministic.
     */
    static Sort parseSort(@Nullable String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return Sort.by("title", "id");
        }
        String[] parts = sortBy.split(",", -1);
        String property = SORTABLE_FIELDS.get(parts[0].trim());
        if (property == null || parts.length > 2) {
            throw invalidSortBy();
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            direction = switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw invalidSortBy();
            };
        }
        return Sort.by(direction, property).and(Sort.by("id"));
    }

    private static RequestValidationException invalidSortBy() {
        return new RequestValidationException(Map.of("sortBy", List.of(SORT_BY_RULE)));
    }

    private Product findProduct(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
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
