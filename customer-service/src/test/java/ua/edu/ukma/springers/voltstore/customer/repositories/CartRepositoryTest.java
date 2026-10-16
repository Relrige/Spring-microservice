package ua.edu.ukma.springers.voltstore.customer.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.customer.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.entities.Cart;
import ua.edu.ukma.springers.voltstore.customer.entities.CartLine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Hibernate validate runs on context start, so a passing test also proves the entities match the V2 schema
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
// Each step commits on its own, as in production where every change is its own service transaction
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CartRepositoryTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Autowired
    private CartRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM cart_items");
        jdbc.update("DELETE FROM carts");
    }

    @Test
    void createIfMissing_twice_givesOneRow() {
        repository.createIfMissing(customerId);
        repository.createIfMissing(customerId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE customer_id = ?", Integer.class,
                customerId)).isEqualTo(1);
    }

    @Test
    void lockedCart_storesItemChanges() {
        repository.createIfMissing(customerId);

        change(cart -> {
            cart.add(A, 2);
            cart.add(B, 1);
        });
        change(cart -> cart.setQuantity(A, 5));

        assertThat(read()).containsExactly(new CartLine(A, 5), new CartLine(B, 1));
    }

    @Test
    void replaceAll_keepingAProduct_doesNotHitThePrimaryKey() {
        repository.createIfMissing(customerId);
        change(cart -> cart.add(A, 1));

        change(cart -> cart.replaceAll(List.of(new CartLine(A, 3), new CartLine(B, 2))));

        assertThat(read()).containsExactly(new CartLine(A, 3), new CartLine(B, 2));
    }

    @Test
    void clear_deletesItems_butKeepsTheCartRow() {
        repository.createIfMissing(customerId);
        change(cart -> cart.add(A, 1));

        change(Cart::clear);

        assertThat(read()).isEmpty();
        assertThat(repository.existsById(customerId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class)).isZero();
    }

    @Test
    void changes_moveUpdatedAtForward() {
        repository.createIfMissing(customerId);
        Instant created = repository.findById(customerId).orElseThrow().getUpdatedAt();

        change(cart -> cart.add(A, 1));

        assertThat(repository.findById(customerId).orElseThrow().getUpdatedAt()).isAfter(created);
    }

    @Test
    void checkConstraint_rejectsQuantityOutsideOneToTen() {
        repository.createIfMissing(customerId);

        for (int quantity : new int[]{0, 11}) {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?, ?, ?)",
                    customerId, UUID.randomUUID(), quantity))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void itemWithoutCart_isRejectedByTheForeignKey() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?, ?, 1)",
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void change(Consumer<Cart> change) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                change.accept(repository.findByIdForUpdate(customerId).orElseThrow()));
    }

    private List<CartLine> read() {
        return new TransactionTemplate(transactionManager).execute(status ->
                repository.findById(customerId).orElseThrow().lines());
    }
}
