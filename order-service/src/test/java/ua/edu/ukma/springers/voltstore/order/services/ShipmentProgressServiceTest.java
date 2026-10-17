package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.ShipmentMilestoneEvent;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/** Each milestone from each status: only the expected one moves the order; everything else changes nothing. */
@ExtendWith(MockitoExtension.class)
class ShipmentProgressServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Mock
    private OrderRepository orders;

    @InjectMocks
    private ShipmentProgressService service;

    @Test
    void ttnGenerated_movesAPaidOrderToPendingShipping() {
        OrderEntity order = stored(OrderStatus.PAID);

        service.ttnGenerated(event(order.getId()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_SHIPPING);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PAID")
    void ttnGenerated_inAnyOtherStatus_changesNothing(OrderStatus status) {
        // Duplicate, worker faster, cancelled meanwhile, or never paid
        OrderEntity order = stored(status);

        assertThatCode(() -> service.ttnGenerated(event(order.getId()))).doesNotThrowAnyException();
        assertThat(order.getStatus()).isEqualTo(status);
    }

    @Test
    void orderDelivered_movesAShippedOrderToDelivered() {
        OrderEntity order = stored(OrderStatus.SHIPPED);

        service.delivered(event(order.getId()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "SHIPPED")
    void orderDelivered_inAnyOtherStatus_changesNothing(OrderStatus status) {
        OrderEntity order = stored(status);

        assertThatCode(() -> service.delivered(event(order.getId()))).doesNotThrowAnyException();
        assertThat(order.getStatus()).isEqualTo(status);
    }

    @Test
    void anUnknownOrder_isIgnored() {
        UUID unknown = UUID.randomUUID();
        when(orders.findById(unknown)).thenReturn(Optional.empty());

        assertThatCode(() -> service.ttnGenerated(event(unknown))).doesNotThrowAnyException();
        assertThatCode(() -> service.delivered(event(unknown))).doesNotThrowAnyException();
    }

    private OrderEntity stored(OrderStatus status) {
        OrderEntity order = OrderEntity.place(UUID.randomUUID(), UUID.randomUUID(), "ivan@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 1)),
                "UAH", NOW.minus(Duration.ofHours(1)), Duration.ofMinutes(15));
        switch (status) {
            case PENDING_PAYMENT -> {
            }
            case PAID -> order.markPaid(NOW);
            case PENDING_SHIPPING -> {
                order.markPaid(NOW);
                order.markPendingShipping();
            }
            case SHIPPED -> {
                order.markPaid(NOW);
                order.markShipped();
            }
            case DELIVERED -> {
                order.markPaid(NOW);
                order.markShipped();
                order.markDelivered();
            }
            case CANCELLATION_REQUESTED -> {
                order.markPaid(NOW);
                order.requestCancellation(NOW);
            }
            case CANCELLED -> {
                order.markPaid(NOW);
                order.requestCancellation(NOW);
                order.markCancelled();
            }
            case EXPIRED -> order.expire();
        }
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        return order;
    }

    private static ShipmentMilestoneEvent event(UUID orderId) {
        return new ShipmentMilestoneEvent(UUID.randomUUID(), orderId, "20450012345678", NOW);
    }
}
