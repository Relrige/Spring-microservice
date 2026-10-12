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
import ua.edu.ukma.springers.voltstore.inventory.entities.ReservationStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemReservationEntity;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockForReservationException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.ReservationAlreadyExistsException;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemReservationRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.ReservationService;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.Concurrently;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class ReserveStockIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockService stockService;

    @Autowired
    private StockItemRepository stockItemRepository;

    @Autowired
    private StockItemReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM stock_item_reservations");
        jdbcTemplate.update("DELETE FROM stock_items");
    }

    @Test
    void reserve_enoughStock_returns204AndPersistsQuantitiesAndRows() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(10);
        UUID productB = stocked(4);

        reserveHttp("{\"orderId\":\"" + orderId + "\",\"items\":["
                + "{\"productId\":\"" + productA + "\",\"quantity\":3},"
                + "{\"productId\":\"" + productB + "\",\"quantity\":4}]}")
                .andExpect(status().isNoContent());

        assertStock(productA, 7, 3);
        assertStock(productB, 0, 4);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE))
                .extracting(StockItemReservationEntity::getProductId, StockItemReservationEntity::getQuantity)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(productA, 3),
                        org.assertj.core.groups.Tuple.tuple(productB, 4));
    }

    @Test
    void reserve_oneItemShort_returns422AndChangesNothing() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(10);
        UUID productB = stocked(1);
        UUID unknown = UUID.randomUUID();

        reserveHttp("{\"orderId\":\"" + orderId + "\",\"items\":["
                + "{\"productId\":\"" + productA + "\",\"quantity\":3},"
                + "{\"productId\":\"" + productB + "\",\"quantity\":2},"
                + "{\"productId\":\"" + unknown + "\",\"quantity\":1}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.insufficientItems.length()").value(2));

        assertStock(productA, 10, 0);
        assertStock(productB, 1, 0);
        assertThat(stockItemRepository.findById(unknown)).isEmpty();
        assertThat(reservationRepository.count()).isZero();
    }

    @Test
    void reserve_sameOrderTwice_returns409AndHoldsStockOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(10);
        String body = "{\"orderId\":\"" + orderId + "\",\"items\":[{\"productId\":\"" + productA + "\",\"quantity\":2}]}";

        reserveHttp(body).andExpect(status().isNoContent());
        reserveHttp(body).andExpect(status().isConflict());

        assertStock(productA, 8, 2);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    void reserve_manyOrdersRaceForLastUnit_exactlyOneSucceeds() throws Exception {
        UUID productA = stocked(1);
        int threads = 8;

        List<Object> outcomes = Concurrently.run(threads,
                () -> reserve(UUID.randomUUID(), Map.of(productA, 1)));

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
        assertThat(outcomes).filteredOn(InsufficientStockForReservationException.class::isInstance)
                .hasSize(threads - 1);
        assertStock(productA, 0, 1);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    void reserve_sameOrderRetriedConcurrently_oneSucceedsOthersConflict() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(10);
        UUID productB = stocked(10);
        int threads = 4;

        List<Object> outcomes = Concurrently.run(threads,
                () -> reserve(orderId, Map.of(productA, 2, productB, 1)));

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
        assertThat(outcomes).filteredOn(ReservationAlreadyExistsException.class::isInstance).hasSize(threads - 1);
        assertStock(productA, 8, 2);
        assertStock(productB, 9, 1);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE)).hasSize(2);
    }

    @Test
    void reserve_twoOrdersWithSameProductsInOppositeOrder_bothSucceedWithoutDeadlock() throws Exception {
        UUID productA = stocked(10);
        UUID productB = stocked(10);
        // Insertion-ordered maps: the callers list the products in opposite order
        Map<UUID, Integer> ab = new LinkedHashMap<>();
        ab.put(productA, 1);
        ab.put(productB, 1);
        Map<UUID, Integer> ba = new LinkedHashMap<>();
        ba.put(productB, 1);
        ba.put(productA, 1);

        List<Object> outcomes = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            outcomes.addAll(Concurrently.run(
                    () -> reserve(UUID.randomUUID(), ab),
                    () -> reserve(UUID.randomUUID(), ba)));
        }

        assertThat(outcomes).containsOnlyNulls();
        assertStock(productA, 0, 10);
        assertStock(productB, 0, 10);
    }

    private UUID stocked(int quantity) {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, quantity);
        return productId;
    }

    private Object reserve(UUID orderId, Map<UUID, Integer> quantities) {
        reservationService.reserve(orderId, quantities);
        return null;
    }

    private void assertStock(UUID productId, int available, int reserved) {
        StockItemEntity item = stockItemRepository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).as("available").isEqualTo(available);
        assertThat(item.getReservedQuantity()).as("reserved").isEqualTo(reserved);
    }

    private org.springframework.test.web.servlet.ResultActions reserveHttp(String body) throws Exception {
        return mockMvc.perform(post("/inventory/reservations")
                .headers(TrustedHeaders.asInternalService())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
