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
import ua.edu.ukma.springers.voltstore.inventory.entities.ReservationStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemReservationEntity;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemReservationRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.ReservationService;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.Concurrently;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class ReleaseReservationIntegrationTest {

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
    void release_activeReservations_returns204RestoresStockAndMarksRowsReleased() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(10);
        UUID productB = stocked(3);
        reservationService.reserve(orderId, Map.of(productA, 4, productB, 3));

        releaseHttp(orderId).andExpect(status().isNoContent());

        assertStock(productA, 10, 0);
        assertStock(productB, 3, 0);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE)).isEmpty();
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RELEASED)).hasSize(2);
    }

    @Test
    void release_unknownOrder_returns204AndChangesNothing() throws Exception {
        UUID productA = stocked(5);

        releaseHttp(UUID.randomUUID()).andExpect(status().isNoContent());

        assertStock(productA, 5, 0);
        assertThat(reservationRepository.count()).isZero();
    }

    @Test
    void release_twice_restoresStockOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(10);
        reservationService.reserve(orderId, Map.of(productA, 4));

        releaseHttp(orderId).andExpect(status().isNoContent());
        releaseHttp(orderId).andExpect(status().isNoContent());

        assertStock(productA, 10, 0);
    }

    @Test
    void release_concurrentCalls_restoreStockExactlyOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(10);
        UUID productB = stocked(10);
        reservationService.reserve(orderId, Map.of(productA, 4, productB, 2));
        // Another order's hold on the same product must stay untouched
        reservationService.reserve(UUID.randomUUID(), Map.of(productA, 1));

        List<Object> outcomes = Concurrently.run(6, () -> {
            reservationService.release(orderId);
            return null;
        });

        // No call failed, in particular none hit CHECK (reserved_quantity >= 0)
        assertThat(outcomes).containsOnlyNulls();
        assertStock(productA, 9, 1);
        assertStock(productB, 10, 0);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RELEASED)).hasSize(2);
    }

    @Test
    void release_leavesConsumedRowsUntouched() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(10);
        UUID productB = stocked(10);
        reservationService.reserve(orderId, Map.of(productA, 2, productB, 3));
        // Simulate a mixed state: the reservation for B was already consumed (shipped)
        StockItemReservationEntity consumed = reservationRepository
                .findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE).stream()
                .filter(r -> r.getProductId().equals(productB)).findFirst().orElseThrow();
        consumed.setStatus(ReservationStatus.CONSUMED);
        reservationRepository.saveAndFlush(consumed);

        reservationService.release(orderId);

        assertStock(productA, 10, 0);
        assertStock(productB, 7, 3);
        assertThat(reservationRepository.findById(consumed.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONSUMED);
    }

    @Test
    void release_thenSameOrderCanBeReservedAgain() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(5);
        reservationService.reserve(orderId, Map.of(productA, 5));
        reservationService.release(orderId);

        reservationService.reserve(orderId, Map.of(productA, 2));

        assertStock(productA, 3, 2);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE)).hasSize(1);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RELEASED)).hasSize(1);
    }

    private UUID stocked(int quantity) {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, quantity);
        return productId;
    }

    private void assertStock(UUID productId, int available, int reserved) {
        StockItemEntity item = stockItemRepository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).as("available").isEqualTo(available);
        assertThat(item.getReservedQuantity()).as("reserved").isEqualTo(reserved);
    }

    private ResultActions releaseHttp(UUID orderId) throws Exception {
        return mockMvc.perform(post("/inventory/reservations/" + orderId + "/release")
                .headers(TrustedHeaders.asInternalService()));
    }
}
