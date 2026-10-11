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
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.repositories.CategoryRepository;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class CategoryIntegrationTest {
    private static final String URL = "/catalog/categories";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // Spies let a test skip a service pre-check to simulate losing a race against a concurrent request
    @MockitoSpyBean
    private CategoryRepository categoryRepository;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @BeforeEach
    void cleanTables() {
        clean(jdbc);
    }

    // --- EP-CAT-06: create ---

    @Test
    void create_persistsTrimmedNameAndReturns201() throws Exception {
        String body = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("  Laptops ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Laptops"))
                .andReturn().getResponse().getContentAsString();

        String id = jdbc.queryForObject("SELECT id FROM categories", String.class);
        assertThat(body).contains("\"categoryId\":\"" + id + "\"");
        assertThat(jdbc.queryForObject("SELECT name FROM categories", String.class)).isEqualTo("Laptops");
    }

    @Test
    void create_nameDiffersOnlyByCaseAndWhitespace_returns409() throws Exception {
        insertCategory(jdbc, "Laptops");

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json(" laptops ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Category name already in use"));

        assertThat(countCategories()).isEqualTo(1);
    }

    @Test
    void create_blankName_returns400AndPersistsNothing() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").isNotEmpty());

        assertThat(countCategories()).isZero();
    }

    @Test
    void create_concurrentRequestsWithSameName_exactlyOneCreatedRestConflict() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String name = i % 2 == 0 ? "Race" : "RACE";
                results.add(executor.submit(() -> {
                    startTogether.await();
                    return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json(name)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
            assertThat(countCategories()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // --- EP-CAT-07: rename ---

    @Test
    void rename_returns200AndUpdatesName() throws Exception {
        UUID id = insertCategory(jdbc, "Laptops");

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json(" Notebooks ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Notebooks"));

        assertThat(nameOf(id)).isEqualTo("Notebooks");
    }

    @Test
    void rename_toOwnNameOrOnlyCasing_succeeds() throws Exception {
        UUID id = insertCategory(jdbc, "laptops");

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json("laptops")))
                .andExpect(status().isOk());
        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json("Laptops")))
                .andExpect(status().isOk());

        assertThat(nameOf(id)).isEqualTo("Laptops");
    }

    @Test
    void rename_unknownId_returns404() throws Exception {
        mockMvc.perform(put(URL + "/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json("X")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rename_unknownIdAndBlankName_returns404BecauseExistenceIsCheckedFirst() throws Exception {
        mockMvc.perform(put(URL + "/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json(" ")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rename_blankName_returns400() throws Exception {
        UUID id = insertCategory(jdbc, "Laptops");

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json(" ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").isNotEmpty());

        assertThat(nameOf(id)).isEqualTo("Laptops");
    }

    @Test
    void rename_nameOfAnotherCategory_returns409() throws Exception {
        insertCategory(jdbc, "Laptops");
        UUID phones = insertCategory(jdbc, "Phones");

        mockMvc.perform(put(URL + "/" + phones).contentType(MediaType.APPLICATION_JSON).content(json("LAPTOPS")))
                .andExpect(status().isConflict());

        assertThat(nameOf(phones)).isEqualTo("Phones");
    }

    @Test
    void rename_losingRaceOnUniqueIndex_returns409() throws Exception {
        insertCategory(jdbc, "Laptops");
        UUID phones = insertCategory(jdbc, "Phones");
        // Pretend the pre-check ran before a concurrent request took the name
        doReturn(false).when(categoryRepository).existsByNameIgnoreCaseAndIdNot(any(), any());

        mockMvc.perform(put(URL + "/" + phones).contentType(MediaType.APPLICATION_JSON).content(json("laptops")))
                .andExpect(status().isConflict());

        assertThat(nameOf(phones)).isEqualTo("Phones");
    }

    @Test
    void rename_nonUuidId_returns400() throws Exception {
        mockMvc.perform(put(URL + "/abc").contentType(MediaType.APPLICATION_JSON).content(json("X")))
                .andExpect(status().isBadRequest());
    }

    // --- EP-CAT-08: delete ---

    @Test
    void delete_categoryWithoutProducts_returns200AndDeletes() throws Exception {
        UUID id = insertCategory(jdbc, "Laptops");

        mockMvc.perform(delete(URL + "/" + id))
                .andExpect(status().isOk());

        assertThat(countCategories()).isZero();
    }

    @Test
    void delete_unknownId_returns404() throws Exception {
        mockMvc.perform(delete(URL + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_categoryWithActiveProduct_returns409AndKeepsCategory() throws Exception {
        UUID id = insertCategory(jdbc, "Laptops");
        insertProduct(jdbc, id, ProductStatus.ACTIVE, StockStatus.IN_STOCK);

        mockMvc.perform(delete(URL + "/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Category has associated products. Reassign or remove all products before deletion."));

        assertThat(countCategories()).isEqualTo(1);
    }

    @Test
    void delete_categoryWithInactiveProduct_returns409AndKeepsCategory() throws Exception {
        UUID id = insertCategory(jdbc, "Laptops");
        insertProduct(jdbc, id, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        mockMvc.perform(delete(URL + "/" + id))
                .andExpect(status().isConflict());

        assertThat(countCategories()).isEqualTo(1);
    }

    @Test
    void delete_losingRaceAgainstProductCreation_returns409FromForeignKey() throws Exception {
        UUID id = insertCategory(jdbc, "Laptops");
        insertProduct(jdbc, id, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);
        // Pretend the product was created after the "has products" check
        doReturn(false).when(productRepository).existsByCategoryId(id);

        mockMvc.perform(delete(URL + "/" + id))
                .andExpect(status().isConflict());

        assertThat(countCategories()).isEqualTo(1);
    }

    private Integer countCategories() {
        return jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class);
    }

    private String nameOf(UUID id) {
        return jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, id);
    }

    private static String json(String name) {
        return "{\"name\":\"" + name + "\"}";
    }
}
