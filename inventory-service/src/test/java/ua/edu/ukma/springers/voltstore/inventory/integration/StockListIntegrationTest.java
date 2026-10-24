package ua.edu.ukma.springers.voltstore.inventory.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductEvent;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.CatalogKnowsEveryProduct;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 24 (EP-INV-01) end to end on PostgreSQL: search, product status filter and title sort on the copied product
 * data, combined with the existing filter and paging.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, CatalogKnowsEveryProduct.class})
class StockListIntegrationTest {
    private static final String URL = "/inventory/stock";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM stock_item_reservations");
        jdbc.update("DELETE FROM stock_items");
    }

    @Test
    void search_combinesWithPackagingConfigured_andPages() throws Exception {
        UUID laptopA = row("Laptop Alpha", "ACTIVE", false);
        UUID laptopB = row("laptop beta", "NOT_ACTIVE", false);
        UUID laptopC = row("LAPTOP Gamma", "ACTIVE", false);
        row("Laptop packed", "ACTIVE", true);
        row("Phone", "ACTIVE", false);
        noCopyRow();

        // productId order is the default; the three matching rows on two pages of two
        List<UUID> expected = List.of(laptopA, laptopB, laptopC).stream().sorted(this::byDatabaseOrder).toList();
        list(get(URL).param("search", "  LAPTOP ").param("packagingConfigured", "false").param("size", "2"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2));
        assertThat(ids(get(URL).param("search", "laptop").param("packagingConfigured", "false")
                .param("size", "2").param("page", "0")))
                .containsExactlyElementsOf(expected.subList(0, 2));
        assertThat(ids(get(URL).param("search", "laptop").param("packagingConfigured", "false")
                .param("size", "2").param("page", "1")))
                .containsExactlyElementsOf(expected.subList(2, 3));
    }

    @Test
    void blankSearch_meansNoFilter() throws Exception {
        row("Laptop", "ACTIVE", false);
        noCopyRow();

        list(get(URL).param("search", "   ")).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void searchForPercentOrUnderscore_matchesLiterally() throws Exception {
        UUID percent = row("Charger 50% off", "ACTIVE", false);
        row("Charger 50 off", "ACTIVE", false);
        row("Anything", "ACTIVE", false);

        assertThat(ids(get(URL).param("search", "50%"))).containsExactly(percent);
        assertThat(ids(get(URL).param("search", "_"))).isEmpty();
    }

    @Test
    void sortByTitle_ascendingAndDescending_putRowsWithoutTitleLast() throws Exception {
        UUID b = row("Bravo", "ACTIVE", false);
        UUID a = row("alpha", "ACTIVE", false);
        UUID c = row("Charlie", "ACTIVE", false);
        UUID noCopy1 = noCopyRow();
        UUID noCopy2 = noCopyRow();
        List<UUID> noCopy = List.of(noCopy1, noCopy2).stream().sorted(this::byDatabaseOrder).toList();

        List<UUID> ascending = ids(get(URL).param("sortBy", "title"));
        List<UUID> descending = ids(get(URL).param("sortBy", "title,desc"));

        // The database collation decides where "alpha" goes relative to the capitalised titles; in every collation
        // the copied titles come first, Bravo before Charlie, and the rows without a title last
        assertThat(ascending.subList(0, 3)).containsExactlyInAnyOrder(a, b, c);
        assertThat(ascending.subList(3, 5)).containsExactlyElementsOf(noCopy);
        assertThat(descending.subList(0, 3)).containsExactlyElementsOf(ascending.subList(0, 3).reversed());
        assertThat(descending.subList(3, 5)).containsExactlyElementsOf(noCopy);
        assertThat(ascending.indexOf(b)).isLessThan(ascending.indexOf(c));
    }

    @Test
    void productStatusFilter_matchesTheCopiedStatusOnly() throws Exception {
        row("Active", "ACTIVE", false);
        UUID inactive = row("Inactive", "NOT_ACTIVE", false);
        noCopyRow();

        assertThat(ids(get(URL).param("productStatus", "NOT_ACTIVE"))).containsExactly(inactive);
    }

    @Test
    void rows_carryTitleAndProductStatus_orNullWithoutACopy() throws Exception {
        UUID copied = row("Laptop", "NOT_ACTIVE", false);
        UUID notCopied = noCopyRow();

        list(get(URL).param("productStatus", "NOT_ACTIVE"))
                .andExpect(jsonPath("$.content[0].productId").value(copied.toString()))
                .andExpect(jsonPath("$.content[0].title").value("Laptop"))
                .andExpect(jsonPath("$.content[0].productStatus").value("NOT_ACTIVE"));
        list(get(URL).param("packagingConfigured", "false").param("sortBy", "title"))
                .andExpect(jsonPath("$.content[1].productId").value(notCopied.toString()))
                .andExpect(jsonPath("$.content[1].title").doesNotExist());
    }

    @Test
    void invalidProductStatus_returns400() throws Exception {
        mockMvc.perform(get(URL).headers(TrustedHeaders.asUser("INVENTORY_WORKER")).param("productStatus", "ARCHIVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.productStatus[0]").isNotEmpty());
    }

    private UUID row(String title, String status, boolean packagingConfigured) {
        UUID productId = UUID.randomUUID();
        stockService.applyProductUpdated(new ProductEvent(UUID.randomUUID(), productId, title, status, 0L, null));
        if (packagingConfigured) {
            jdbc.update("UPDATE stock_items SET gross_weight_grams = 500, length_cm = 10, width_cm = 5, height_cm = 2 "
                    + "WHERE product_id = ?", productId);
        }
        return productId;
    }

    private UUID noCopyRow() {
        UUID productId = UUID.randomUUID();
        stockService.ensureStockItem(productId);
        return productId;
    }

    // PostgreSQL orders UUIDs like their text form (Java's UUID.compareTo does not)
    private int byDatabaseOrder(UUID left, UUID right) {
        return left.toString().compareTo(right.toString());
    }

    private ResultActions list(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.headers(TrustedHeaders.asUser("INVENTORY_WORKER"))).andExpect(status().isOk());
    }

    private List<UUID> ids(MockHttpServletRequestBuilder request) throws Exception {
        String json = list(request).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$.content[*].productId");
        return ids.stream().map(UUID::fromString).toList();
    }
}
