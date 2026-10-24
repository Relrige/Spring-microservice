package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.CatalogKnowsEveryProduct;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EP-INV-08 (task 25): the worker's read of one stock item, packaging included whether complete or not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, CatalogKnowsEveryProduct.class})
class GetStockItemIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StockService stockService;

    @Autowired
    private StockItemRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.update("DELETE FROM stock_item_reservations");
        jdbcTemplate.update("DELETE FROM stock_items");
    }

    @Test
    void configuredPackaging_returnsStockRowAndStoredValues() throws Exception {
        UUID productId = UUID.randomUUID();
        stockService.setPackaging(productId, 500, 10, 5, 2);
        stockService.adjustStock(productId, 7);

        getStockItem(productId, TrustedHeaders.asUser("INVENTORY_WORKER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.availableQuantity").value(7))
                .andExpect(jsonPath("$.reservedQuantity").value(0))
                .andExpect(jsonPath("$.totalQuantity").value(7))
                .andExpect(jsonPath("$.packagingConfigured").value(true))
                .andExpect(jsonPath("$.grossWeightGrams").value(500))
                .andExpect(jsonPath("$.lengthCm").value(10))
                .andExpect(jsonPath("$.widthCm").value(5))
                .andExpect(jsonPath("$.heightCm").value(2));
    }

    @Test
    void packagingNotSet_returns200WithNullValues() throws Exception {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, 3);

        getStockItem(productId, TrustedHeaders.asUser("INVENTORY_WORKER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packagingConfigured").value(false))
                .andExpect(jsonPath("$.grossWeightGrams").doesNotExist())
                .andExpect(jsonPath("$.lengthCm").doesNotExist())
                .andExpect(jsonPath("$.widthCm").doesNotExist())
                .andExpect(jsonPath("$.heightCm").doesNotExist());
    }

    @Test
    void unknownProduct_returns404AndCreatesNothing() throws Exception {
        UUID productId = UUID.randomUUID();

        getStockItem(productId, TrustedHeaders.asUser("INVENTORY_WORKER")).andExpect(status().isNotFound());

        assertThat(repository.findById(productId)).isEmpty();
    }

    @Test
    void otherRolesAndInternalServices_get403() throws Exception {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, 1);

        getStockItem(productId, TrustedHeaders.asUser("CATALOG_MANAGER")).andExpect(status().isForbidden());
        getStockItem(productId, TrustedHeaders.asUser("CUSTOMER")).andExpect(status().isForbidden());
        getStockItem(productId, TrustedHeaders.asInternalService()).andExpect(status().isForbidden());
    }

    @Test
    void anonymousCaller_gets401() throws Exception {
        getStockItem(UUID.randomUUID(), new HttpHeaders()).andExpect(status().isUnauthorized());
    }

    private ResultActions getStockItem(UUID productId, HttpHeaders headers) throws Exception {
        return mockMvc.perform(get("/inventory/stock/" + productId).headers(headers));
    }
}
