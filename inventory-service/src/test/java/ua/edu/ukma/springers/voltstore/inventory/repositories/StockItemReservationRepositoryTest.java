package ua.edu.ukma.springers.voltstore.inventory.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.entities.ReservationStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemReservationEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PostgresTestContainerConfig.class)
class StockItemReservationRepositoryTest {
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();

    @Autowired
    private StockItemReservationRepository repository;

    @Autowired
    private StockItemRepository stockItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createStockItems() {
        jdbcTemplate.update("DELETE FROM stock_item_reservations");
        jdbcTemplate.update("DELETE FROM stock_items");
        stockItemRepository.saveAndFlush(StockItemEntity.builder().productId(PRODUCT_A).build());
        stockItemRepository.saveAndFlush(StockItemEntity.builder().productId(PRODUCT_B).build());
    }

    @Test
    void save_generatesIdDefaultsToActiveAndSetsTimestamps() {
        UUID orderId = UUID.randomUUID();

        StockItemReservationEntity saved = repository.saveAndFlush(reservation(orderId, PRODUCT_A, 3).build());

        StockItemReservationEntity found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getId()).isNotNull();
        assertThat(found.getOrderId()).isEqualTo(orderId);
        assertThat(found.getProductId()).isEqualTo(PRODUCT_A);
        assertThat(found.getQuantity()).isEqualTo(3);
        assertThat(found.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void status_isStoredAsString() {
        StockItemReservationEntity saved = repository.saveAndFlush(
                reservation(UUID.randomUUID(), PRODUCT_A, 1).status(ReservationStatus.CONSUMED).build());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM stock_item_reservations WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("CONSUMED");
    }

    @Test
    void statusChange_updatesUpdatedAtAndKeepsCreatedAt() throws InterruptedException {
        StockItemReservationEntity saved = repository.saveAndFlush(reservation(UUID.randomUUID(), PRODUCT_A, 1).build());
        Instant createdAt = saved.getCreatedAt();
        Instant updatedAt = saved.getUpdatedAt();
        Thread.sleep(5);

        saved.setStatus(ReservationStatus.RELEASED);
        repository.saveAndFlush(saved);

        assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
        assertThat(saved.getUpdatedAt()).isAfter(updatedAt);
    }

    @Test
    void findByOrderIdAndStatus_returnsOnlyThatOrdersRowsWithThatStatus() {
        UUID orderId = UUID.randomUUID();
        StockItemReservationEntity activeA = repository.save(reservation(orderId, PRODUCT_A, 1).build());
        StockItemReservationEntity activeB = repository.save(reservation(orderId, PRODUCT_B, 2).build());
        repository.save(reservation(orderId, PRODUCT_A, 5).status(ReservationStatus.RELEASED).build());
        repository.save(reservation(UUID.randomUUID(), PRODUCT_A, 1).build());
        repository.flush();

        assertThat(repository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE))
                .extracting(StockItemReservationEntity::getId)
                .containsExactlyInAnyOrder(activeA.getId(), activeB.getId());
    }

    @Test
    void existsByOrderIdAndStatus_onlyCountsActiveRowsOfThatOrder() {
        UUID activeOrder = UUID.randomUUID();
        UUID releasedOrder = UUID.randomUUID();
        repository.save(reservation(activeOrder, PRODUCT_A, 1).build());
        repository.save(reservation(releasedOrder, PRODUCT_A, 1).status(ReservationStatus.RELEASED).build());
        repository.flush();

        assertThat(repository.existsByOrderIdAndStatus(activeOrder, ReservationStatus.ACTIVE)).isTrue();
        assertThat(repository.existsByOrderIdAndStatus(releasedOrder, ReservationStatus.ACTIVE)).isFalse();
        assertThat(repository.existsByOrderIdAndStatus(UUID.randomUUID(), ReservationStatus.ACTIVE)).isFalse();
    }

    @Test
    void secondActiveReservationForSameOrderAndProduct_violatesPartialUniqueIndex() {
        UUID orderId = UUID.randomUUID();
        repository.saveAndFlush(reservation(orderId, PRODUCT_A, 1).build());

        assertThatThrownBy(() -> repository.saveAndFlush(reservation(orderId, PRODUCT_A, 2).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"RELEASED", "CONSUMED"})
    void terminalRowForSameOrderAndProduct_isAllowedNextToActiveRow(ReservationStatus terminal) {
        UUID orderId = UUID.randomUUID();
        repository.saveAndFlush(reservation(orderId, PRODUCT_A, 1).status(terminal).build());
        repository.saveAndFlush(reservation(orderId, PRODUCT_A, 1).status(terminal).build());

        repository.saveAndFlush(reservation(orderId, PRODUCT_A, 1).build());

        assertThat(repository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE)).hasSize(1);
        assertThat(repository.findByOrderIdAndStatus(orderId, terminal)).hasSize(2);
    }

    @Test
    void activeReservationsForSameOrderAndDifferentProducts_areAllowed() {
        UUID orderId = UUID.randomUUID();

        repository.saveAndFlush(reservation(orderId, PRODUCT_A, 1).build());
        repository.saveAndFlush(reservation(orderId, PRODUCT_B, 1).build());

        assertThat(repository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void save_quantityBelowOne_violatesCheck(int quantity) {
        StockItemReservationEntity reservation = reservation(UUID.randomUUID(), PRODUCT_A, quantity).build();

        assertThatThrownBy(() -> repository.saveAndFlush(reservation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_unknownStockItem_violatesForeignKey() {
        StockItemReservationEntity reservation = reservation(UUID.randomUUID(), UUID.randomUUID(), 1).build();

        assertThatThrownBy(() -> repository.saveAndFlush(reservation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownStatusValue_violatesCheck() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stock_item_reservations (id, order_id, product_id, quantity, status) "
                        + "VALUES (?, ?, ?, 1, 'EXPIRED')",
                UUID.randomUUID(), UUID.randomUUID(), PRODUCT_A))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static StockItemReservationEntity.StockItemReservationEntityBuilder reservation(UUID orderId,
                                                                                         UUID productId,
                                                                                         int quantity) {
        return StockItemReservationEntity.builder().orderId(orderId).productId(productId).quantity(quantity);
    }
}
