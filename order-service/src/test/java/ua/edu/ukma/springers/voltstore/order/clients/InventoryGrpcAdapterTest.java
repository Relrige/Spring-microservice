package ua.edu.ukma.springers.voltstore.order.clients;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.grpc.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.grpc.client.autoconfigure.CompositeChannelFactoryAutoConfiguration;
import org.springframework.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveItem;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveStockResponse;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.ports.StockLevels;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.Insufficient;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.ReservationItem;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.Reserved;
import ua.edu.ukma.springers.voltstore.order.support.FakeInventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ua.edu.ukma.springers.voltstore.order.support.FakeInventory.LEVELS;
import static ua.edu.ukma.springers.voltstore.order.support.FakeInventory.RELEASE;
import static ua.edu.ukma.springers.voltstore.order.support.FakeInventory.RELEASE_ALL;
import static ua.edu.ukma.springers.voltstore.order.support.FakeInventory.RESERVE;

/**
 * Order's gRPC client of Inventory (task grpc-order-inventory): the four call types, and the channel's retry policy
 * exactly as application.yml configures it (4 attempts, UNAVAILABLE only), against {@link FakeInventory} on a
 * local port (Netty, as in production).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = {
        InventoryGrpcConfiguration.class,
        InventoryGrpcAdapter.class,
        InventoryGrpcAdapterTest.Properties.class,
        GrpcClientAutoConfiguration.class,
        CompositeChannelFactoryAutoConfiguration.class,
        SslAutoConfiguration.class,
        AopAutoConfiguration.class,
        CircuitBreakerAutoConfiguration.class
})
class InventoryGrpcAdapterTest {
    private static final String TOKEN = "internal-token-internal-token-1234567";
    private static final UUID ORDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PHONE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LAPTOP = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final FakeInventory inventory = new FakeInventory().startOnPort(0);

    @EnableConfigurationProperties(InternalTokenProperties.class)
    static class Properties {
    }

    @DynamicPropertySource
    static void channel(DynamicPropertyRegistry registry) {
        registry.add("spring.grpc.client.channel.inventory.target", () -> "localhost:" + inventory.port());
    }

    @Autowired
    private StockReservations reservations;

    @Autowired
    private StockLevels levels;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @AfterAll
    static void stopInventory() {
        inventory.stop();
    }

    @BeforeEach
    void reset() {
        inventory.reset();
        circuitBreakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
    }

    // --- Client streaming: reserve ---

    @Test
    void reserve_streamsEveryItemOfTheOrder_withTheInternalToken() {
        assertThat(reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 2), new ReservationItem(LAPTOP, 1))))
                .isEqualTo(new Reserved());

        assertThat(inventory.reserveCalls()).singleElement().satisfies(items -> assertThat(items)
                .extracting(ReserveItem::getOrderId, ReserveItem::getProductId, ReserveItem::getQuantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ORDER.toString(), PHONE.toString(), 2),
                        org.assertj.core.groups.Tuple.tuple(ORDER.toString(), LAPTOP.toString(), 1)));
        assertThat(inventory.tokens()).containsOnly(TOKEN);
    }

    @Test
    void reserve_alreadyReserved_meansAnEarlierAttemptReserved() {
        inventory.answerReserve(ReserveStockResponse.Outcome.ALREADY_RESERVED);

        assertThat(reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 2)))).isEqualTo(new Reserved());
    }

    @Test
    void reserve_insufficient_returnsInventorysShortItems() {
        inventory.answerReserveInsufficient(PHONE, 5, 2);

        assertThat(reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 5))))
                .isEqualTo(new Insufficient(List.of(new InsufficientItem(PHONE, 5, 2))));
    }

    // --- Unary: release ---

    @Test
    void release_isOneUnaryCall() {
        reservations.release(ORDER);

        assertThat(inventory.released()).containsExactly(ORDER);
        assertThat(inventory.attempts(RELEASE)).isEqualTo(1);
    }

    // --- Bidirectional: releaseAll ---

    @Test
    void releaseAll_releasesTheBatchOnOneStream_andReportsOnlyTheFailedOrders() {
        UUID failing = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        inventory.failInBatch(failing);

        Map<UUID, String> failed = reservations.releaseAll(List.of(ORDER, failing, other));

        assertThat(failed).containsOnlyKeys(failing);
        assertThat(inventory.batchReleased()).containsExactly(ORDER, other);
        assertThat(inventory.attempts(RELEASE_ALL)).isEqualTo(1);
    }

    // --- Server streaming: availableQuantities ---

    @Test
    void availableQuantities_oneLevelPerProduct_unknownIsZero() {
        inventory.stock(PHONE, 7);

        assertThat(levels.availableQuantities(List.of(PHONE, LAPTOP))).containsExactly(
                Map.entry(PHONE, 7), Map.entry(LAPTOP, 0));
    }

    // --- Retries (channel retry policy) ---

    @Test
    void unavailableTwice_isRetried_andTheThirdAttemptSucceeds(CapturedOutput output) {
        inventory.failNext(RESERVE, 2, Status.Code.UNAVAILABLE);

        assertThat(reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 1)))).isEqualTo(new Reserved());

        assertThat(inventory.attempts(RESERVE)).isEqualTo(3);
        // The client stream was replayed: the successful attempt received the order's items again
        assertThat(inventory.reserveCalls()).singleElement().satisfies(items -> assertThat(items).hasSize(1));
        assertThat(output).contains("gRPC Inventory ReserveStock: attempt 1 failed with UNAVAILABLE",
                "gRPC Inventory ReserveStock: attempt 2 (retry 1 of the call)",
                "gRPC Inventory ReserveStock: attempt 3 (retry 2 of the call)");
    }

    @Test
    void retriesWorkForEveryCallType() {
        inventory.failNext(RELEASE, 1, Status.Code.UNAVAILABLE);
        reservations.release(ORDER);
        assertThat(inventory.attempts(RELEASE)).isEqualTo(2);

        inventory.failNext(LEVELS, 1, Status.Code.UNAVAILABLE);
        levels.availableQuantities(List.of(PHONE));
        assertThat(inventory.attempts(LEVELS)).isEqualTo(2);

        inventory.failNext(RELEASE_ALL, 1, Status.Code.UNAVAILABLE);
        assertThat(reservations.releaseAll(List.of(ORDER))).isEmpty();
        assertThat(inventory.attempts(RELEASE_ALL)).isEqualTo(2);
    }

    @Test
    void unavailableEveryTime_endsAfterFourAttempts_asDependencyUnavailable() {
        inventory.failAlways(RESERVE, Status.Code.UNAVAILABLE);

        assertThatThrownBy(() -> reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 1))))
                .isInstanceOfSatisfying(DependencyUnavailableException.class,
                        e -> assertThat(e.getService()).isEqualTo("Inventory"));
        assertThat(inventory.attempts(RESERVE)).isEqualTo(4);
    }

    @Test
    void otherStatuses_areNotRetried() {
        inventory.failAlways(RELEASE, Status.Code.INTERNAL);

        assertThatThrownBy(() -> reservations.release(ORDER)).isInstanceOf(DependencyUnavailableException.class);
        assertThat(inventory.attempts(RELEASE)).isEqualTo(1);
    }
}
