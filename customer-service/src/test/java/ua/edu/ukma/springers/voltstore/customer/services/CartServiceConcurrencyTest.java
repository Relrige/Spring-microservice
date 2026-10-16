package ua.edu.ukma.springers.voltstore.customer.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ua.edu.ukma.springers.voltstore.customer.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.entities.CartLine;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartRuleViolation;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartRuleViolationException;
import ua.edu.ukma.springers.voltstore.customer.support.Concurrently;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pessimistic lock serialises writers of one cart: without it, parallel read-then-write would lose updates,
 * exceed the limits, or fail on the primary key. Threads are released together to provoke those races.
 */
@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class CartServiceConcurrencyTest {
    private static final UUID PRODUCT = UUID.randomUUID();

    @Autowired
    private CartService cartService;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM cart_items");
        jdbc.update("DELETE FROM carts");
    }

    @Test
    void parallelFirstAdds_createOneCart_withoutDuplicateKeyErrors() throws Exception {
        List<Object> results = Concurrently.run(IntStream.range(0, 8)
                .mapToObj(i -> (Callable<?>) () -> cartService.changeCreatingIfMissing(customerId,
                        cart -> cart.add(UUID.randomUUID(), 1)))
                .toArray(Callable<?>[]::new));

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(List.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class)).isEqualTo(1);
        assertThat(cartService.getCart(customerId)).hasSize(8);
    }

    @Test
    void tenParallelIncrements_endAtExactlyTen() throws Exception {
        List<Object> results = Concurrently.run(10,
                () -> cartService.changeCreatingIfMissing(customerId, cart -> cart.add(PRODUCT, 1)));

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(List.class));
        assertThat(cartService.getCart(customerId)).containsExactly(new CartLine(PRODUCT, 10));
    }

    @Test
    void elevenParallelIncrements_rejectExactlyOne() throws Exception {
        List<Object> results = Concurrently.run(11,
                () -> cartService.changeCreatingIfMissing(customerId, cart -> cart.add(PRODUCT, 1)));

        assertThat(violations(results, CartRuleViolation.QUANTITY_LIMIT_EXCEEDED)).isEqualTo(1);
        assertThat(cartService.getCart(customerId)).containsExactly(new CartLine(PRODUCT, 10));
    }

    @Test
    void elevenParallelNewProducts_rejectExactlyOne() throws Exception {
        List<Object> results = Concurrently.run(IntStream.range(0, 11)
                .mapToObj(i -> (Callable<?>) () -> cartService.changeCreatingIfMissing(customerId,
                        cart -> cart.add(UUID.randomUUID(), 1)))
                .toArray(Callable<?>[]::new));

        assertThat(violations(results, CartRuleViolation.TOO_MANY_PRODUCTS)).isEqualTo(1);
        assertThat(cartService.getCart(customerId)).hasSize(10);
    }

    @Test
    void readsAndChangesWithoutCart_createNoRow() {
        assertThat(cartService.getCart(customerId)).isEmpty();
        assertThat(cartService.changeIfExists(customerId, cart -> cart.remove(PRODUCT))).isEmpty();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class)).isZero();
    }

    private static long violations(List<Object> results, CartRuleViolation violation) {
        assertThat(results).allSatisfy(result -> assertThat(result)
                .isInstanceOfAny(List.class, CartRuleViolationException.class));
        return results.stream()
                .filter(CartRuleViolationException.class::isInstance)
                .map(CartRuleViolationException.class::cast)
                .filter(ex -> ex.getViolation() == violation)
                .count();
    }
}
