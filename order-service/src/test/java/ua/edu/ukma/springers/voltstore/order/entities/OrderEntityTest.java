package ua.edu.ukma.springers.voltstore.order.entities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEntityTest {
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final UUID PHONE = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();

    @Test
    void place_computesTheTotalFromTheSnapshots_andStartsTheWindow() {
        OrderEntity order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        // 2 x 19999.50 + 1 x 499.00
        assertThat(order.getTotalAmount()).isEqualByComparingTo("40498.00");
        assertThat(order.getReservationExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(order.getPaidAt()).isNull();
        assertThat(order.getVersion()).isNull();
    }

    @Test
    void place_rejectsAnEmptyOrderAndADuplicateProduct() {
        assertThatThrownBy(() -> place(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> place(List.of(item(PHONE, "1.00", 1), item(PHONE, "1.00", 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    void item_rejectsQuantityZeroAndANegativePrice() {
        assertThatThrownBy(() -> item(PHONE, "1.00", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(PHONE, "-1.00", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void happyPath_paidPendingShippingShippedDelivered() {
        OrderEntity order = newOrder();

        order.markPaid(NOW.plusSeconds(60));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(NOW.plusSeconds(60));

        order.markPendingShipping();
        order.markShipped();
        order.markDelivered();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void aPaidOrderCanBeShippedBeforeTheTtnEventArrives() {
        OrderEntity order = newOrder();
        order.markPaid(NOW);

        order.markShipped();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void expire_onlyFromPendingPayment() {
        OrderEntity order = newOrder();
        order.expire();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);

        OrderEntity paid = newOrder();
        paid.markPaid(NOW);
        assertThatThrownBy(paid::expire).isInstanceOf(InvalidOrderStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "PAID", "PENDING_SHIPPING"})
    void requestCancellation_isAllowedBeforeShipping_andRemembersWhereItStarted(OrderStatus start) {
        OrderEntity order = orderIn(start);

        order.requestCancellation(NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
        assertThat(order.getStatusBeforeCancellation()).isEqualTo(start);
        assertThat(order.refundOwed()).isEqualTo(start != OrderStatus.PENDING_PAYMENT);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "DELIVERED", "CANCELLATION_REQUESTED", "CANCELLED",
            "EXPIRED"})
    void requestCancellation_isRejectedOtherwise(OrderStatus start) {
        OrderEntity order = orderIn(start);

        assertThatThrownBy(() -> order.requestCancellation(NOW))
                .isInstanceOfSatisfying(InvalidOrderStateException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(start));
    }

    @Test
    void cancellationFailures_areCounted_andFlagTheOrderAfterTheLimit() {
        OrderEntity order = newOrder();
        order.requestCancellation(NOW);

        for (int attempt = 1; attempt <= 4; attempt++) {
            order.recordCancellationFailure("refund: 502", NOW.plusSeconds(attempt), 5);
        }
        assertThat(order.getCancellationAttempts()).isEqualTo(4);
        assertThat(order.isNeedsManualAttention()).isFalse();

        order.recordCancellationFailure("refund: 502", NOW.plusSeconds(5), 5);

        assertThat(order.isNeedsManualAttention()).isTrue();
        assertThat(order.getCancellationFailure()).isEqualTo("refund: 502");
        assertThat(order.getCancellationLastAttemptAt()).isEqualTo(NOW.plusSeconds(5));

        order.markCancelled();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationFailure()).isNull();
    }

    @Test
    void forbiddenTransitions_throwAndLeaveTheStatusUnchanged() {
        OrderEntity order = newOrder();

        List<Consumer<OrderEntity>> forbidden = List.of(OrderEntity::markPendingShipping, OrderEntity::markShipped,
                OrderEntity::markDelivered, OrderEntity::markCancelled,
                o -> o.recordCancellationFailure("x", NOW, 5));
        for (Consumer<OrderEntity> transition : forbidden) {
            assertThatThrownBy(() -> transition.accept(order)).isInstanceOf(InvalidOrderStateException.class);
        }
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        order.markPaid(NOW);
        assertThatThrownBy(() -> order.markPaid(NOW)).isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void belongsTo_comparesTheCustomer() {
        OrderEntity order = newOrder();

        assertThat(order.belongsTo(order.getCustomerId())).isTrue();
        assertThat(order.belongsTo(UUID.randomUUID())).isFalse();
    }

    /** An order brought into the given status through the legal transitions. */
    static OrderEntity orderIn(OrderStatus status) {
        OrderEntity order = newOrder();
        switch (status) {
            case PENDING_PAYMENT -> { }
            case PAID -> order.markPaid(NOW);
            case PENDING_SHIPPING -> { order.markPaid(NOW); order.markPendingShipping(); }
            case SHIPPED -> { order.markPaid(NOW); order.markShipped(); }
            case DELIVERED -> { order.markPaid(NOW); order.markShipped(); order.markDelivered(); }
            case CANCELLATION_REQUESTED -> order.requestCancellation(NOW);
            case CANCELLED -> { order.requestCancellation(NOW); order.markCancelled(); }
            case EXPIRED -> order.expire();
        }
        return order;
    }

    static OrderEntity newOrder() {
        return place(List.of(item(PHONE, "19999.50", 2), item(CASE, "499.00", 1)));
    }

    private static OrderEntity place(List<OrderItemEntity> items) {
        return OrderEntity.place(UUID.randomUUID(), UUID.randomUUID(), "customer@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                items, "UAH", NOW, Duration.ofMinutes(15));
    }

    private static OrderItemEntity item(UUID productId, String price, int quantity) {
        return OrderItemEntity.snapshot(productId, "Product " + productId, new BigDecimal(price), quantity);
    }
}
