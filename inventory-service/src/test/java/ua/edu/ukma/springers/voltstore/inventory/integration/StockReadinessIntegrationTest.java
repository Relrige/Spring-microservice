package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class StockReadinessIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM stock_items");
    }

    @Test
    void checkReadiness_packagingConfigured_returns204() throws Exception {
        UUID productId = UUID.randomUUID();
        stockService.setPackaging(productId, 500, 10, 5, 2);

        getReadiness(productId).andExpect(status().isNoContent());
    }

    @Test
    void checkReadiness_unknownProduct_returns404AndDoesNotCreateStockItem() throws Exception {
        UUID productId = UUID.randomUUID();

        getReadiness(productId).andExpect(status().isNotFound());

        assertThat(repository.findById(productId)).isEmpty();
    }

    @Test
    void checkReadiness_stockWithoutPackaging_returns422() throws Exception {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, 3);

        getReadiness(productId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("packaging_data_missing"));
    }

    private ResultActions getReadiness(UUID productId) throws Exception {
        return mockMvc.perform(get("/inventory/stock/" + productId + "/readiness")
                .headers(TrustedHeaders.asInternalService()));
    }
}
