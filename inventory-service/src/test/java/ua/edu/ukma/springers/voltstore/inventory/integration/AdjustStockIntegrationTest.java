package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class AdjustStockIntegrationTest {
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
    void adjust_firstRestock_createsStockItemAndPersistsQuantity() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(post("/inventory/stock/" + productId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newAvailableQuantity").value(10));

        var item = repository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(10);
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(item.isPackagingConfigured()).isFalse();
    }

    @Test
    void adjust_writeOffBelowZero_returns422AndKeepsQuantity() throws Exception {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, 3);

        mockMvc.perform(post("/inventory/stock/" + productId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-4}"))
                .andExpect(status().isUnprocessableContent());

        assertThat(repository.findById(productId).orElseThrow().getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void adjust_writeOffForUnknownProduct_returns422AndDoesNotCreateStockItem() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(post("/inventory/stock/" + productId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-1}"))
                .andExpect(status().isUnprocessableContent());

        assertThat(repository.findById(productId)).isEmpty();
    }

    @Test
    void adjust_concurrentWriteOffs_neverTakeQuantityBelowZero() throws Exception {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, 5);

        List<Object> outcomes = runConcurrently(
                () -> stockService.adjustStock(productId, -3),
                () -> stockService.adjustStock(productId, -3));

        assertThat(outcomes).filteredOn(o -> o instanceof Integer).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof InsufficientStockException).hasSize(1);
        assertThat(repository.findById(productId).orElseThrow().getAvailableQuantity()).isEqualTo(2);
    }

    @Test
    void adjust_concurrentFirstTimeAdjustments_createOneStockItemAndApplyBothDeltas() throws Exception {
        UUID productId = UUID.randomUUID();

        List<Object> outcomes = runConcurrently(
                () -> stockService.adjustStock(productId, 4),
                () -> stockService.adjustStock(productId, 6));

        assertThat(outcomes).allMatch(o -> o instanceof Integer);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock_items WHERE product_id = ?", Integer.class, productId)).isEqualTo(1);
        assertThat(repository.findById(productId).orElseThrow().getAvailableQuantity()).isEqualTo(10);
    }

    /**
     * Starts all tasks at the same moment and returns each task's result or thrown exception.
     */
    @SafeVarargs
    private List<Object> runConcurrently(Callable<Integer>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch ready = new CountDownLatch(tasks.length);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
