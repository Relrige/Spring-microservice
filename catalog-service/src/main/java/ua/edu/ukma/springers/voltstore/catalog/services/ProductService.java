package ua.edu.ukma.springers.voltstore.catalog.services;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.catalog.dto.BatchProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.PageResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSearchQuery;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductStatusRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSummaryResponse;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.events.ProductEvent;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundForProductException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductActivationRejectedException;
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
    // EP-CAT-09 and the ids filter of EP-CAT-04: an order never has this many distinct products, and a page
    // holds at most this many rows; bounds the size of the IN (...) query
    public static final int BATCH_GET_MAX_IDS = 100;

    // sortBy values accepted by EP-CAT-04, mapped to entity properties. Never sort by an arbitrary property name.
    private static final Map<String, String> SORTABLE_FIELDS = Map.of("title", "title", "basePrice", "basePrice");
    private static final String SORT_BY_RULE = "must be title or basePrice, optionally followed by ,asc or ,desc";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final RequestValidator requestValidator;
    private final Outbox outbox;
    private final StockReadinessChecker stockReadinessChecker;
    private final TransactionOperations transactionOperations;

    @Transactional
    public CreateProductResponse createProduct(ProductRequest request) {
        requireCategoryExists(request.getCategoryId());

        Product product = Product.createNew(request.getTitle().trim(), request.getDescription().trim(),
                request.getCategoryId(), request.getBasePrice());
        saveAndFlush(product);

        // Same transaction as the product: the event is stored if and only if the product is (outbox)
        appendEvent(ProductEvent.CREATED, product);
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
        // The flush runs the UPDATE now: an overlapping save fails here (409) before any event is stored, and the
        // event below carries the incremented version. An update that changes nothing issues no UPDATE; its event
        // repeats the current version, which consumers ignore.
        saveAndFlush(product);
        appendEvent(ProductEvent.UPDATED, product);
        return ProductResponse.from(product);
    }

    /**
     * EP-CAT-03. Order of checks follows the spec: 404, then 400, then (activation only) the Inventory readiness
     * check (422 not ready, 503 Inventory unavailable). Only {@code status} changes (thanks to
     * {@code @DynamicUpdate}, {@code stockStatus} is never written). {@code ProductUpdated} is stored only when the
     * status actually changed. An overlapping save of the same product fails with 409 (optimistic lock).
     * <p>
     * Not {@code @Transactional}: the readiness call to Inventory (up to several seconds with timeouts and a
     * retry) must not hold a database transaction or connection. The existence check runs in its own short
     * read transaction, and the status write in a second short transaction after the remote call.
     */
    public void changeStatus(UUID id, ProductStatusRequest request) {
        findProduct(id);
        requestValidator.validate(request);

        ProductStatus status = request.getStatus();
        if (status == ProductStatus.ACTIVE) {
            // Re-run even if the product is already ACTIVE: activation always means "ready right now", and an
            // already active product that is no longer ready gets 422 (its status stays ACTIVE)
            StockReadiness readiness = stockReadinessChecker.check(id);
            if (!readiness.ready()) {
                throw new ProductActivationRejectedException(id, readiness.reason());
            }
        }
        transactionOperations.executeWithoutResult(tx -> {
            Product product = findProduct(id);
            if (product.getStatus() == status) {
                return;
            }
            switch (status) {
                case ACTIVE -> product.activate();
                case NOT_ACTIVE -> product.deactivate();
            }
            productRepository.flush();
            appendEvent(ProductEvent.UPDATED, product);
        });
    }

    /**
     * EP-CAT-05: the full view for every caller, under the same visibility rule as EP-CAT-04. Staff see a product
     * of any status; for everyone else an inactive product gets exactly the same 404 as a missing one, so the
     * existence of unpublished products does not leak.
     */
    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID id, @Nullable UserRole callerRole) {
        ProductStatus visible = visibleStatus(null, callerRole);
        return productRepository.findById(id)
                .filter(product -> visible == null || product.getStatus() == visible)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    /**
     * EP-CAT-04. Staff see every product and may filter by {@code status}; every other caller (anonymous
     * included, {@code callerRole == null}) sees ACTIVE products only and the {@code status} parameter is
     * ignored. {@code stockStatus} comes from the stored read-model column; Inventory is not called.
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
        if (query.getIds() != null) {
            filters.add(ProductSpecifications.idIn(distinctIds(query.getIds())));
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
        Set<UUID> distinctIds = distinctIds(ids);
        Map<UUID, Product> found = productRepository.findAllById(distinctIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return distinctIds.stream()
                .map(found::get)
                .filter(Objects::nonNull)
                .map(BatchProductResponse::from)
                .toList();
    }


    /**
     * Role-based product visibility for EP-CAT-04 and EP-CAT-05 (business logic, not a security rule): the status
     * filter to apply, or null for no status filter. Only staff ({@code CATALOG_MANAGER} and
     * {@code INVENTORY_WORKER}, who prepare products before activation) see NOT_ACTIVE products; {@code ADMIN},
     * customers, anonymous callers and internal calls do not.
     */
    static @Nullable ProductStatus visibleStatus(@Nullable ProductStatus requested, @Nullable UserRole callerRole) {
        return isStaff(callerRole) ? requested : ProductStatus.ACTIVE;
    }

    private static boolean isStaff(@Nullable UserRole role) {
        return role == UserRole.CATALOG_MANAGER || role == UserRole.INVENTORY_WORKER;
    }

    /**
     * The {@code ids} of EP-CAT-04 and EP-CAT-09: a non-empty list of at most {@link #BATCH_GET_MAX_IDS} distinct
     * UUIDs, in request order. Duplicates count once.
     */
    private static Set<UUID> distinctIds(@Nullable Collection<UUID> ids) {
        // A list such as "?ids=a,,b" binds an empty element as null
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(Objects::isNull)) {
            throw new RequestValidationException(Map.of("ids", List.of("must be a non-empty list of UUIDs")));
        }
        Set<UUID> distinctIds = new LinkedHashSet<>(ids);
        if (distinctIds.size() > BATCH_GET_MAX_IDS) {
            throw new RequestValidationException(Map.of("ids",
                    List.of("must contain at most " + BATCH_GET_MAX_IDS + " IDs")));
        }
        return distinctIds;
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

    // Must run in the caller's transaction, after the product was flushed (see ProductEvent.of)
    private void appendEvent(String eventType, Product product) {
        ProductEvent event = ProductEvent.of(product);
        outbox.append("Product", product.getId(), eventType, KafkaTopics.PRODUCT_EVENTS, event.eventId(), event);
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
