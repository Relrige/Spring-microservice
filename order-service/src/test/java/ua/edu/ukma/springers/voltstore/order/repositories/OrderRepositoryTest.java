package ua.edu.ukma.springers.voltstore.order.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.order.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class OrderRepositoryTest {
    // Truncated to microseconds: PostgreSQL stores no finer, so values compare equal after a round trip
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final UUID CUSTOMER = UUID.randomUUID();

    @Autowired
    private OrderRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    void save_storesTheOrderWithItsSnapshots_andReadsThemBack() {
        OrderEntity saved = repository.saveAndFlush(order(CUSTOMER, NOW));

        OrderEntity found = repository.findByIdAndCustomerId(saved.getId(), CUSTOMER).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(found.getCustomerEmail()).isEqualTo("customer@example.com");
        assertThat(found.getTotalAmount()).isEqualByComparingTo("40498.00");
        assertThat(found.getDeliveryDetails().getCarrier()).isEqualTo("NOVA_POSHTA");
        assertThat(found.getPlacedAt()).isEqualTo(NOW);
        assertThat(found.getVersion()).isZero();
        assertThat(found.getItems()).extracting(OrderItemEntity::getProductTitle)
                .containsExactlyInAnyOrder("Phone", "Case");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, saved.getId()))
                .as("enum stored as its name").isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void findByIdAndCustomerId_doesNotFindAnotherCustomersOrder() {
        OrderEntity saved = repository.saveAndFlush(order(CUSTOMER, NOW));

        assertThat(repository.findByIdAndCustomerId(saved.getId(), UUID.randomUUID())).isEmpty();
        assertThat(repository.findByIdAndCustomerId(UUID.randomUUID(), CUSTOMER)).isEmpty();
    }

    @Test
    void findByCustomerId_pagesTheCustomersOwnOrders_newestFirst() {
        OrderEntity oldest = repository.save(order(CUSTOMER, NOW.minusSeconds(300)));
        OrderEntity middle = repository.save(order(CUSTOMER, NOW.minusSeconds(200)));
        OrderEntity newest = repository.save(order(CUSTOMER, NOW.minusSeconds(100)));
        repository.save(order(UUID.randomUUID(), NOW));
        repository.flush();

        Page<OrderEntity> first = repository.findByCustomerId(CUSTOMER,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "placedAt")));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(OrderEntity::getId).containsExactly(newest.getId(), middle.getId());
        assertThat(repository.findByCustomerId(CUSTOMER, PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "placedAt")))
                .getContent()).extracting(OrderEntity::getId).containsExactly(oldest.getId());
    }

    @Test
    void findByStatusIn_returnsTheFulfillmentQueue_oldestPaymentFirst() {
        OrderEntity later = order(CUSTOMER, NOW);
        later.markPaid(NOW.plusSeconds(20));
        OrderEntity earlier = order(CUSTOMER, NOW);
        earlier.markPaid(NOW.plusSeconds(10));
        earlier.markPendingShipping();
        repository.saveAll(List.of(later, earlier, order(CUSTOMER, NOW)));
        repository.flush();

        assertThat(repository.findByStatusInOrderByPaidAtAsc(OrderStatus.AWAITING_DISPATCH))
                .extracting(OrderEntity::getId).containsExactly(earlier.getId(), later.getId());
    }

    @Test
    void findExpired_returnsOnlyUnpaidOrdersPastTheGivenInstant() {
        OrderEntity expired = repository.save(order(CUSTOMER, NOW.minus(Duration.ofMinutes(20))));
        repository.save(order(CUSTOMER, NOW));
        OrderEntity paidLongAgo = order(CUSTOMER, NOW.minus(Duration.ofMinutes(20)));
        paidLongAgo.markPaid(NOW.minus(Duration.ofMinutes(19)));
        repository.save(paidLongAgo);
        repository.flush();

        assertThat(repository.findExpired(NOW.minusSeconds(30), Limit.of(10)))
                .extracting(OrderEntity::getId).containsExactly(expired.getId());
    }

    @Test
    void findInterruptedCancellations_putsNeverRetriedFirst() {
        OrderEntity retried = order(CUSTOMER, NOW);
        retried.requestCancellation();
        retried.recordCancellationFailure("release: 503", NOW, 5);
        OrderEntity fresh = order(CUSTOMER, NOW);
        fresh.requestCancellation();
        repository.saveAll(List.of(retried, fresh, order(CUSTOMER, NOW)));
        repository.flush();

        assertThat(repository.findInterruptedCancellations(Limit.of(10)))
                .extracting(OrderEntity::getId).containsExactly(fresh.getId(), retried.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentChangesOfOneOrder_theSecondFailsOnTheVersion() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID id = tx.execute(status -> repository.save(order(CUSTOMER, NOW)).getId());

        // Two requests read the same version; both change the status
        OrderEntity first = tx.execute(status -> repository.findById(id).orElseThrow());
        OrderEntity second = tx.execute(status -> repository.findById(id).orElseThrow());
        first.markPaid(NOW);
        second.requestCancellation();
        tx.executeWithoutResult(status -> repository.save(first));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.save(second)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        OrderStatus stored = tx.execute(status -> repository.findById(id).orElseThrow().getStatus());
        assertThat(stored).isEqualTo(OrderStatus.PAID);
    }

    // One violation per test: after the first one PostgreSQL aborts the test's transaction
    @Test
    void aNegativePrice_isRejectedByTheDatabase() {
        UUID orderId = repository.saveAndFlush(order(CUSTOMER, NOW)).getId();

        assertThatThrownBy(() -> insertItem(orderId, new BigDecimal("-1"), 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void quantityZero_isRejectedByTheDatabase() {
        UUID orderId = repository.saveAndFlush(order(CUSTOMER, NOW)).getId();

        assertThatThrownBy(() -> insertItem(orderId, BigDecimal.ONE, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertItem(UUID orderId, BigDecimal price, int quantity) {
        jdbcTemplate.update("INSERT INTO order_items (id, order_id, product_id, product_title, unit_price_at_order, "
                + "quantity) VALUES (?, ?, ?, 'X', ?, ?)", UUID.randomUUID(), orderId, UUID.randomUUID(), price, quantity);
    }

    @Test
    void aSecondLineForTheSameProduct_violatesTheUniqueConstraint() {
        OrderEntity saved = repository.saveAndFlush(order(CUSTOMER, NOW));
        UUID productId = saved.getItems().getFirst().getProductId();

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO order_items (id, order_id, product_id, "
                        + "product_title, unit_price_at_order, quantity) VALUES (?, ?, ?, 'X', 1, 1)",
                UUID.randomUUID(), saved.getId(), productId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_order_items_order_product");
    }

    @Test
    void aPaidStatusWithoutPaidAt_isRejectedByTheDatabase() {
        UUID id = repository.saveAndFlush(order(CUSTOMER, NOW)).getId();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE orders SET status = 'PAID' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_orders_paid_at_once_paid");
    }

    private static OrderEntity order(UUID customerId, Instant placedAt) {
        return OrderEntity.place(UUID.randomUUID(), customerId, "customer@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("19999.50"), 2),
                        OrderItemEntity.snapshot(UUID.randomUUID(), "Case", new BigDecimal("499.00"), 1)),
                "UAH", placedAt, Duration.ofMinutes(15));
    }
}
