package ua.edu.ukma.springers.voltstore.catalog.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.services.StockReadiness;
import ua.edu.ukma.springers.voltstore.catalog.services.StockReadinessChecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.clean;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertCategory;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertProduct;
import static ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders.asInternalService;
import static ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders.asManager;
import static ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders.asUser;

// Tasks 13–17 end to end: real security chain, real PostgreSQL
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogReadAndStatusIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // Activation here is about visibility; the Inventory call itself is tested in ProductActivationIntegrationTest
    @MockitoBean
    private StockReadinessChecker stockReadinessChecker;

    private UUID categoryId;

    @BeforeEach
    void setUp() {
        clean(jdbc);
        categoryId = insertCategory(jdbc, "Laptops");
        when(stockReadinessChecker.check(any())).thenReturn(StockReadiness.isReady());
    }

    // --- EP-CAT-10: list categories ---

    @Test
    void listCategories_anonymousSearchWithPaging() throws Exception {
        insertCategory(jdbc, "Gaming laptops");
        insertCategory(jdbc, "Phones");
        insertCategory(jdbc, "50% off");

        mockMvc.perform(get("/catalog/categories").param("search", "LAPTOP").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Gaming laptops"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/catalog/categories").param("search", "50%"))
                .andExpect(jsonPath("$.content[*].name", containsInAnyOrder("50% off")));
        mockMvc.perform(get("/catalog/categories").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lists_reportTheEffectivePageSize() throws Exception {
        insertActive(categoryId, "Ultrabook", "light laptop", "1500.00");

        // size is the effective page size (default 20), not the number of items on the page
        for (String url : new String[]{"/catalog/categories", "/catalog/products"}) {
            mockMvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.size").value(20));
            mockMvc.perform(get(url).param("size", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(5));
        }
    }

    // --- EP-CAT-03: change status ---

    @Test
    void changeStatus_activateThenDeactivate_controlsCustomerVisibility() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.IN_STOCK);
        mockMvc.perform(get("/catalog/products/" + id)).andExpect(status().isNotFound());

        changeStatus(id, "ACTIVE").andExpect(status().isOk());
        mockMvc.perform(get("/catalog/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockStatus").value("IN_STOCK"));

        changeStatus(id, "NOT_ACTIVE").andExpect(status().isOk());
        mockMvc.perform(get("/catalog/products/" + id)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT stock_status FROM products WHERE id = ?", String.class, id))
                .isEqualTo("IN_STOCK");
    }

    @Test
    void changeStatus_sameStatusTwice_isIdempotent() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        changeStatus(id, "NOT_ACTIVE").andExpect(status().isOk());
        changeStatus(id, "NOT_ACTIVE").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM products WHERE id = ?", String.class, id))
                .isEqualTo("NOT_ACTIVE");
    }

    @Test
    void changeStatus_errors() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        changeStatus(UUID.randomUUID(), "ACTIVE").andExpect(status().isNotFound());
        // Existence is checked before body validation
        mockMvc.perform(patch("/catalog/products/" + UUID.randomUUID() + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/catalog/products/" + id + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").isNotEmpty());
        changeStatus(id, "ARCHIVED").andExpect(status().isBadRequest());
        mockMvc.perform(patch("/catalog/products/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/catalog/products/" + id + "/status").headers(asUser("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT status FROM products WHERE id = ?", String.class, id))
                .isEqualTo("NOT_ACTIVE");
    }

    // --- EP-CAT-05: product details ---

    @Test
    void getProduct_inactiveLooksExactlyLikeMissing_forNonStaff() throws Exception {
        UUID inactive = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);
        UUID missing = UUID.randomUUID();

        for (HttpHeaders caller : nonStaffCallers()) {
            JsonNode inactiveBody = notFoundBody(inactive, caller);
            JsonNode missingBody = notFoundBody(missing, caller);

            assertThat(inactiveBody.get("title")).isEqualTo(missingBody.get("title"));
            assertThat(inactiveBody.get("type")).isEqualTo(missingBody.get("type"));
            assertThat(inactiveBody.get("detail").asString().replace(inactive.toString(), "{id}"))
                    .isEqualTo(missingBody.get("detail").asString().replace(missing.toString(), "{id}"));
        }
    }

    @Test
    void getProduct_activeIsVisibleToEveryCaller_withTheFullView() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.IN_STOCK);

        for (HttpHeaders caller : allCallers()) {
            mockMvc.perform(get("/catalog/products/" + id).headers(caller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productId").value(id.toString()))
                    .andExpect(jsonPath("$.title").value("Laptop"))
                    .andExpect(jsonPath("$.description").value("A laptop"))
                    .andExpect(jsonPath("$.categoryId").value(categoryId.toString()))
                    .andExpect(jsonPath("$.basePrice").value(999.99))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.stockStatus").value("IN_STOCK"));
        }
    }

    @Test
    void getProduct_inactiveIsVisibleToStaff() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        for (String role : new String[]{"CATALOG_MANAGER", "INVENTORY_WORKER"}) {
            mockMvc.perform(get("/catalog/products/" + id).headers(asUser(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value("A laptop"))
                    .andExpect(jsonPath("$.status").value("NOT_ACTIVE"))
                    .andExpect(jsonPath("$.stockStatus").value("OUT_OF_STOCK"));
        }
    }

    // --- EP-CAT-04: browse ---

    @Test
    void browse_visibilityDependsOnTheCaller() throws Exception {
        UUID active = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.IN_STOCK);
        UUID inactive = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        for (HttpHeaders caller : nonStaffCallers()) {
            mockMvc.perform(get("/catalog/products").headers(caller))
                    .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(active.toString())));
            // The status filter is ignored for non-staff: it never exposes NOT_ACTIVE products
            mockMvc.perform(get("/catalog/products").headers(caller).param("status", "NOT_ACTIVE"))
                    .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(active.toString())));
        }
        for (String role : new String[]{"CATALOG_MANAGER", "INVENTORY_WORKER"}) {
            mockMvc.perform(get("/catalog/products").headers(asUser(role)))
                    .andExpect(jsonPath("$.content[*].productId",
                            containsInAnyOrder(active.toString(), inactive.toString())));
            mockMvc.perform(get("/catalog/products").headers(asUser(role)).param("status", "NOT_ACTIVE"))
                    .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(inactive.toString())))
                    .andExpect(jsonPath("$.content[0].stockStatus").value("OUT_OF_STOCK"));
        }
    }

    @Test
    void browse_filtersSortsAndPages() throws Exception {
        UUID phones = insertCategory(jdbc, "Phones");
        UUID cheap = insertActive(phones, "Budget phone", "cheap", "100.00");
        UUID mid = insertActive(phones, "Midrange phone", "good camera", "400.00");
        insertActive(categoryId, "Ultrabook", "light laptop", "1500.00");

        mockMvc.perform(get("/catalog/products").param("categoryId", phones.toString())
                        .param("sortBy", "basePrice,desc").param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(cheap.toString())))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/catalog/products").param("search", "CAMERA").param("minPrice", "100").param("maxPrice", "400"))
                .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(mid.toString())));
        mockMvc.perform(get("/catalog/products").param("categoryId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void browse_idsFilter_combinesWithVisibilityAndOtherFilters() throws Exception {
        UUID ultrabook = insertActive(categoryId, "Ultrabook", "light laptop", "1500.00");
        UUID gaming = insertActive(categoryId, "Gaming laptop", "fast", "2500.00");
        insertActive(categoryId, "Office laptop", "quiet", "700.00");
        UUID inactive = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);
        String listed = ultrabook + "," + gaming + "," + inactive + "," + UUID.randomUUID();

        // Anonymous: the inactive and the unknown ID are simply absent, and totalElements counts what is returned
        mockMvc.perform(get("/catalog/products").param("ids", listed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(ultrabook.toString(), gaming.toString())))
                .andExpect(jsonPath("$.totalElements").value(2));
        // Repeated parameters bind too; duplicates count once
        mockMvc.perform(get("/catalog/products").param("ids", gaming.toString(), gaming.toString())
                        .param("ids", gaming.toString()))
                .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(gaming.toString())))
                .andExpect(jsonPath("$.totalElements").value(1));
        // Combined with search, and with paging and sorting
        mockMvc.perform(get("/catalog/products").param("ids", listed).param("search", "LIGHT"))
                .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(ultrabook.toString())));
        mockMvc.perform(get("/catalog/products").param("ids", listed).param("sortBy", "basePrice,desc").param("size", "1"))
                .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(gaming.toString())))
                .andExpect(jsonPath("$.totalElements").value(2));
        // Staff see the inactive one, and the status filter applies on top
        mockMvc.perform(get("/catalog/products").headers(asUser("INVENTORY_WORKER")).param("ids", listed))
                .andExpect(jsonPath("$.content[*].productId",
                        containsInAnyOrder(ultrabook.toString(), gaming.toString(), inactive.toString())));
        mockMvc.perform(get("/catalog/products").headers(asManager()).param("ids", listed).param("status", "NOT_ACTIVE"))
                .andExpect(jsonPath("$.content[*].productId", containsInAnyOrder(inactive.toString())));
    }

    @Test
    void browse_invalidIds_return400() throws Exception {
        String tooMany = Stream.generate(() -> UUID.randomUUID().toString()).limit(101).collect(Collectors.joining(","));

        mockMvc.perform(get("/catalog/products").param("ids", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ids").isNotEmpty());
        mockMvc.perform(get("/catalog/products").param("ids", UUID.randomUUID() + ",nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ids").isNotEmpty());
        mockMvc.perform(get("/catalog/products").param("ids", UUID.randomUUID() + ",," + UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ids").isNotEmpty());
        mockMvc.perform(get("/catalog/products").param("ids", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ids").isNotEmpty());
        // 101 copies of one ID are one distinct ID
        mockMvc.perform(get("/catalog/products").param("ids", String.join(",", Collections.nCopies(101, UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void browse_invalidParameters_return400() throws Exception {
        mockMvc.perform(get("/catalog/products").param("minPrice", "500").param("maxPrice", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.minPrice").isNotEmpty());
        mockMvc.perform(get("/catalog/products").param("sortBy", "stockStatus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sortBy").isNotEmpty());
        mockMvc.perform(get("/catalog/products").param("status", "GONE"))
                .andExpect(status().isBadRequest());
    }

    // --- EP-CAT-09: batch get ---

    @Test
    void batchGet_returnsExistingProductsOfAnyStatusOnce() throws Exception {
        UUID active = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.IN_STOCK);
        UUID inactive = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        mockMvc.perform(batchGet(active + "," + UUID.randomUUID() + "," + inactive + "," + active))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productId").value(active.toString()))
                .andExpect(jsonPath("$[0].title").value("Laptop"))
                .andExpect(jsonPath("$[0].basePrice").value(999.99))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].stockStatus").value("IN_STOCK"))
                .andExpect(jsonPath("$[1].status").value("NOT_ACTIVE"))
                .andExpect(jsonPath("$[1].stockStatus").value("OUT_OF_STOCK"));
        mockMvc.perform(batchGet(UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void batchGet_returnsStoredStockStatus() throws Exception {
        UUID outOfStock = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.OUT_OF_STOCK);
        UUID inactiveInStock = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.IN_STOCK);

        mockMvc.perform(batchGet(outOfStock + "," + inactiveInStock))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockStatus").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$[1].status").value("NOT_ACTIVE"))
                .andExpect(jsonPath("$[1].stockStatus").value("IN_STOCK"));
    }

    @Test
    void batchGet_invalidRequests_return400() throws Exception {
        String tooMany = Stream.generate(() -> UUID.randomUUID().toString()).limit(101).collect(Collectors.joining(","));

        mockMvc.perform(batchGet("")).andExpect(status().isBadRequest());
        mockMvc.perform(batchGet(UUID.randomUUID() + ",nope")).andExpect(status().isBadRequest());
        mockMvc.perform(batchGet(tooMany)).andExpect(status().isBadRequest());
        mockMvc.perform(batchGet(String.join(",", Collections.nCopies(101, UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/catalog/products/batch-get").headers(asInternalService()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchGet_isInternalOnly() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.IN_STOCK);

        mockMvc.perform(get("/catalog/products/batch-get").param("ids", id.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/catalog/products/batch-get").headers(asUser("ADMIN")).param("ids", id.toString()))
                .andExpect(status().isForbidden());
    }

    private ResultActions changeStatus(UUID id, String status) throws Exception {
        return mockMvc.perform(patch("/catalog/products/" + id + "/status").headers(asManager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    private MockHttpServletRequestBuilder batchGet(String ids) {
        return get("/catalog/products/batch-get").headers(asInternalService()).param("ids", ids);
    }

    // Anonymous, CUSTOMER and ADMIN: everyone who sees ACTIVE products only
    private static List<HttpHeaders> nonStaffCallers() {
        return List.of(new HttpHeaders(), asUser("CUSTOMER"), asUser("ADMIN"));
    }

    private static List<HttpHeaders> allCallers() {
        List<HttpHeaders> callers = new ArrayList<>(nonStaffCallers());
        callers.add(asUser("CATALOG_MANAGER"));
        callers.add(asUser("INVENTORY_WORKER"));
        return callers;
    }

    private JsonNode notFoundBody(UUID id, HttpHeaders caller) throws Exception {
        String body = mockMvc.perform(get("/catalog/products/" + id).headers(caller))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private UUID insertActive(UUID category, String title, String description, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, title, description, category_id, base_price, status, stock_status)
                VALUES (?, ?, ?, ?, ?::numeric, 'ACTIVE', 'IN_STOCK')
                """, id, title, description, category, price);
        return id;
    }
}
