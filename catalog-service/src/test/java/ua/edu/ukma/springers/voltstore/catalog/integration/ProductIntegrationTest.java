package ua.edu.ukma.springers.voltstore.catalog.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.repositories.CategoryRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.clean;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertCategory;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertProduct;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductIntegrationTest {
    private static final String URL = "/catalog/products";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ProductRepository productRepository;

    // Lets a test skip the category pre-check to simulate a category deleted concurrently
    @MockitoSpyBean
    private CategoryRepository categoryRepository;

    private UUID categoryId;

    @BeforeEach
    void setUp() {
        clean(jdbc);
        categoryId = insertCategory(jdbc, "Laptops");
    }

    // --- EP-CAT-01: create ---

    @Test
    void create_persistsInactiveOutOfStockProductAndReturns201() throws Exception {
        String body = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(json(" Laptop ", "A laptop", categoryId, "999.9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM products");
        assertThat(body).contains(row.get("id").toString());
        assertThat(row.get("title")).isEqualTo("Laptop");
        assertThat(row.get("category_id")).isEqualTo(categoryId);
        assertThat(row.get("base_price")).isEqualTo(new BigDecimal("999.90"));
        assertThat(row.get("status")).isEqualTo("NOT_ACTIVE");
        assertThat(row.get("stock_status")).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void create_ignoresStatusAndStockStatusSentByClient() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Laptop","description":"A laptop","categoryId":"%s","basePrice":10,
                         "status":"ACTIVE","stockStatus":"IN_STOCK"}
                        """.formatted(categoryId)))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT status FROM products", String.class)).isEqualTo("NOT_ACTIVE");
        assertThat(jdbc.queryForObject("SELECT stock_status FROM products", String.class)).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void create_invalidFields_returns400AndPersistsNothing() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("", " ", categoryId, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").isNotEmpty())
                .andExpect(jsonPath("$.errors.description").isNotEmpty())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());

        assertThat(countProducts()).isZero();
    }

    @Test
    void create_unknownCategory_returns422() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("Laptop", "A laptop", UUID.randomUUID(), "10")))
                .andExpect(status().isUnprocessableContent());

        assertThat(countProducts()).isZero();
    }

    @Test
    void create_categoryDeletedAfterCheck_returns422FromForeignKey() throws Exception {
        UUID deletedCategoryId = UUID.randomUUID();
        // Pretend the category still existed when it was checked
        doReturn(true).when(categoryRepository).existsById(deletedCategoryId);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("Laptop", "A laptop", deletedCategoryId, "10")))
                .andExpect(status().isUnprocessableContent());

        assertThat(countProducts()).isZero();
    }

    // --- EP-CAT-02: update ---

    @Test
    void update_inactiveProduct_returns200WithUpdatedProduct() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);
        UUID otherCategoryId = insertCategory(jdbc, "Notebooks");

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(json("New title", "New description", otherCategoryId, "1500.5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(id.toString()))
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.description").value("New description"))
                .andExpect(jsonPath("$.categoryId").value(otherCategoryId.toString()))
                .andExpect(jsonPath("$.basePrice").value(1500.50))
                .andExpect(jsonPath("$.status").value("NOT_ACTIVE"))
                .andExpect(jsonPath("$.stockStatus").value("OUT_OF_STOCK"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM products WHERE id = ?", id);
        assertThat(row.get("title")).isEqualTo("New title");
        assertThat(row.get("category_id")).isEqualTo(otherCategoryId);
        assertThat(row.get("base_price")).isEqualTo(new BigDecimal("1500.50"));
    }

    @Test
    void update_activeProduct_keepsStatusAndStockStatusEvenIfClientSendsThem() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.IN_STOCK);

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"New","description":"New","categoryId":"%s","basePrice":5,
                         "status":"NOT_ACTIVE","stockStatus":"OUT_OF_STOCK"}
                        """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stockStatus").value("IN_STOCK"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM products WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("stock_status")).isEqualTo("IN_STOCK");
    }

    @Test
    void update_unknownId_returns404() throws Exception {
        mockMvc.perform(put(URL + "/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Laptop", "A laptop", categoryId, "10")))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_unknownIdWithInvalidBody_returns404BecauseExistenceIsCheckedFirst() throws Exception {
        mockMvc.perform(put(URL + "/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("", "", categoryId, "-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_invalidBodyWithUnknownCategory_returns400BecauseValidationComesBeforeCategoryCheck() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(json("", "A laptop", UUID.randomUUID(), "10")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").isNotEmpty());
    }

    @Test
    void update_missingFields_returns400() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        // PUT replaces all editable fields: a partial body is rejected, not merged
        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Only title\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.description").isNotEmpty())
                .andExpect(jsonPath("$.errors.categoryId").isNotEmpty())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());

        assertThat(jdbc.queryForObject("SELECT title FROM products WHERE id = ?", String.class, id)).isEqualTo("Laptop");
    }

    @Test
    void update_unknownCategory_returns422AndKeepsProduct() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(json("New", "New", UUID.randomUUID(), "10")))
                .andExpect(status().isUnprocessableContent());

        assertThat(jdbc.queryForObject("SELECT category_id FROM products WHERE id = ?", UUID.class, id)).isEqualTo(categoryId);
    }

    @Test
    void update_doesNotOverwriteStockStatusWrittenConcurrentlyByAnEvent() {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.OUT_OF_STOCK);

        transactionTemplate.executeWithoutResult(tx -> {
            Product product = productRepository.findById(id).orElseThrow();
            // An Inventory event commits IN_STOCK in another transaction after the product was loaded
            CompletableFuture.runAsync(() ->
                    jdbc.update("UPDATE products SET stock_status = 'IN_STOCK' WHERE id = ?", id)).join();
            product.updateDetails("New title", "New description", categoryId, new BigDecimal("10"));
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM products WHERE id = ?", id);
        assertThat(row.get("title")).isEqualTo("New title");
        assertThat(row.get("stock_status")).isEqualTo("IN_STOCK");
    }

    private Integer countProducts() {
        return jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
    }

    private static String json(String title, String description, UUID categoryId, String basePrice) {
        return "{\"title\":\"" + title + "\",\"description\":\"" + description + "\",\"categoryId\":\""
                + categoryId + "\",\"basePrice\":" + basePrice + "}";
    }
}
