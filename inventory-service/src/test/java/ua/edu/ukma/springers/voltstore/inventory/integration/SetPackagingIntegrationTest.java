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
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class SetPackagingIntegrationTest {
    private static final String BODY = "{\"grossWeightGrams\":500,\"lengthCm\":10,\"widthCm\":5,\"heightCm\":2}";

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
    void setPackaging_unknownProduct_createsItemWithZeroQuantitiesAndPackaging() throws Exception {
        UUID productId = UUID.randomUUID();

        putPackaging(productId, BODY).andExpect(status().isNoContent());

        StockItemEntity item = repository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).isZero();
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(item.getGrossWeightGrams()).isEqualTo(500);
        assertThat(item.getLengthCm()).isEqualTo(10);
        assertThat(item.getWidthCm()).isEqualTo(5);
        assertThat(item.getHeightCm()).isEqualTo(2);
    }

    @Test
    void setPackaging_existingItem_keepsQuantitiesAndReplacesAllValues() throws Exception {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, 8);

        putPackaging(productId, BODY).andExpect(status().isNoContent());
        putPackaging(productId, "{\"grossWeightGrams\":900,\"lengthCm\":20,\"widthCm\":15,\"heightCm\":12}")
                .andExpect(status().isNoContent());

        StockItemEntity item = repository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(8);
        assertThat(item.getGrossWeightGrams()).isEqualTo(900);
        assertThat(item.getLengthCm()).isEqualTo(20);
        assertThat(item.getWidthCm()).isEqualTo(15);
        assertThat(item.getHeightCm()).isEqualTo(12);
    }

    @Test
    void setPackaging_repeatedWithSameBody_isIdempotent() throws Exception {
        UUID productId = UUID.randomUUID();

        putPackaging(productId, BODY).andExpect(status().isNoContent());
        putPackaging(productId, BODY).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock_items WHERE product_id = ?", Integer.class, productId)).isEqualTo(1);
        assertThat(repository.findById(productId).orElseThrow().getGrossWeightGrams()).isEqualTo(500);
    }

    @Test
    void setPackaging_invalidBody_doesNotCreateStockItem() throws Exception {
        UUID productId = UUID.randomUUID();

        putPackaging(productId, "{\"grossWeightGrams\":0,\"lengthCm\":10,\"widthCm\":5,\"heightCm\":2}")
                .andExpect(status().isBadRequest());

        assertThat(repository.findById(productId)).isEmpty();
    }

    @Test
    void setPackaging_concurrentFirstTimeRequests_createOneStockItem() throws Exception {
        UUID productId = UUID.randomUUID();

        List<Object> outcomes = runConcurrently(
                () -> {
                    stockService.setPackaging(productId, 500, 10, 5, 2);
                    return null;
                },
                () -> {
                    stockService.setPackaging(productId, 700, 12, 6, 3);
                    return null;
                });

        assertThat(outcomes).allMatch(o -> o == null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock_items WHERE product_id = ?", Integer.class, productId)).isEqualTo(1);
        // Whichever request ran last wins as a whole: the four values always come from the same request
        StockItemEntity item = repository.findById(productId).orElseThrow();
        assertThat(item.getGrossWeightGrams()).isIn(500, 700);
        assertThat(item.getGrossWeightGrams() == 500
                ? List.of(10, 5, 2) : List.of(12, 6, 3))
                .containsExactly(item.getLengthCm(), item.getWidthCm(), item.getHeightCm());
    }

    @Test
    void setPackagingAndAdjust_concurrentFirstTimeRequests_shareOneRowAndLoseNothing() throws Exception {
        UUID productId = UUID.randomUUID();

        List<Object> outcomes = runConcurrently(
                () -> {
                    stockService.setPackaging(productId, 500, 10, 5, 2);
                    return null;
                },
                () -> stockService.adjustStock(productId, 6));

        assertThat(outcomes).noneMatch(o -> o instanceof Exception);
        StockItemEntity item = repository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(6);
        assertThat(item.isPackagingConfigured()).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions putPackaging(UUID productId, String body) throws Exception {
        return mockMvc.perform(put("/inventory/stock/" + productId + "/packaging")
                .headers(TrustedHeaders.asUser("INVENTORY_WORKER"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * Starts all tasks at the same moment and returns each task's result or thrown exception.
     */
    private List<Object> runConcurrently(Callable<?>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch ready = new CountDownLatch(tasks.length);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
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
