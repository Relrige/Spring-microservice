package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.dto.FulfillmentQueueResponse;
import ua.edu.ukma.springers.voltstore.order.dto.ShippedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderShippedEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.exceptions.TtnNotGeneratedException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.DeliveryStatus;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.ShipmentInfo;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FulfillmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final String TTN = "20450012345678";

    @Mock
    private OrderRepository orders;

    @Mock
    private Shipments shipments;

    @Mock
    private Outbox outbox;

    private FulfillmentService service;

    @BeforeEach
    void setUp() {
        service = new FulfillmentService(orders, shipments, outbox, TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- EP-ORD-07 ----

    @Test
    void queue_mergesDeliverysAnswer_keepingTheOldestPaymentFirst() {
        OrderEntity first = order(OrderStatus.PAID);
        OrderEntity second = order(OrderStatus.PENDING_SHIPPING);
        OrderEntity third = order(OrderStatus.PAID);
        when(orders.findByStatusInOrderByPaidAtAsc(OrderStatus.AWAITING_DISPATCH))
                .thenReturn(List.of(first, second, third));
        // Delivery answers in its own order, and has no shipment for the third yet
        when(shipments.find(List.of(first.getId(), second.getId(), third.getId()))).thenReturn(List.of(
                new ShipmentInfo(second.getId(), DeliveryStatus.TTN_GENERATED, TTN, null),
                new ShipmentInfo(first.getId(), DeliveryStatus.TTN_FAILED, null, "carrier_error: timeout"),
                new ShipmentInfo(third.getId(), null, null, null)));

        FulfillmentQueueResponse queue = service.queue();

        assertThat(queue.deliveryDataAvailable()).isTrue();
        assertThat(queue.orders()).extracting(FulfillmentQueueResponse.Entry::orderId)
                .containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(queue.orders().get(0).deliveryStatus()).isEqualTo(DeliveryStatus.TTN_FAILED);
        assertThat(queue.orders().get(0).failureReason()).isEqualTo("carrier_error: timeout");
        assertThat(queue.orders().get(1).ttn()).isEqualTo(TTN);
        assertThat(queue.orders().get(2).deliveryStatus()).isNull();
        assertThat(queue.orders().get(0).items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
        assertThat(queue.orders().get(0).deliveryDetails().address()).isEqualTo("Kyiv, branch 12");
    }

    @Test
    void queue_withDeliveryDown_showsTheOrdersWithoutDeliveryData_andSaysSo() {
        OrderEntity order = order(OrderStatus.PAID);
        when(orders.findByStatusInOrderByPaidAtAsc(any())).thenReturn(List.of(order));
        when(shipments.find(any())).thenThrow(new DependencyUnavailableException("Delivery", "down", null));

        FulfillmentQueueResponse queue = service.queue();

        assertThat(queue.deliveryDataAvailable()).isFalse();
        assertThat(queue.orders()).singleElement().satisfies(entry -> {
            assertThat(entry.orderId()).isEqualTo(order.getId());
            assertThat(entry.deliveryStatus()).isNull();
            assertThat(entry.ttn()).isNull();
        });
    }

    @Test
    void anEmptyQueue_doesNotAskDelivery() {
        when(orders.findByStatusInOrderByPaidAtAsc(any())).thenReturn(List.of());

        assertThat(service.queue()).isEqualTo(new FulfillmentQueueResponse(true, List.of()));
        verifyNoInteractions(shipments);
    }

    // ---- EP-ORD-08 ----

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PENDING_SHIPPING"})
    void ship_withAGeneratedTtn_marksShipped_andWritesOrderShipped(OrderStatus status) {
        // Also PAID: Delivery's TTN counts even when its TTNGenerated event is late
        OrderEntity order = stored(order(status));
        delivery(order, DeliveryStatus.TTN_GENERATED, TTN);

        ShippedOrderResponse response = service.ship(order.getId());

        assertThat(response).isEqualTo(new ShippedOrderResponse(order.getId(), OrderStatus.SHIPPED, TTN));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        ArgumentCaptor<OrderShippedEvent> event = ArgumentCaptor.forClass(OrderShippedEvent.class);
        verify(outbox).append(event.capture());
        assertThat(event.getValue().ttn()).isEqualTo(TTN);
        assertThat(event.getValue().customerEmail()).isEqualTo("ivan@example.com");
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"TTN_PENDING", "TTN_FAILED", "CANCELLED", "IN_TRANSIT"})
    void ship_withoutAGeneratedTtn_is409(DeliveryStatus deliveryStatus) {
        OrderEntity order = stored(order(OrderStatus.PAID));
        delivery(order, deliveryStatus, null);

        assertThatThrownBy(() -> service.ship(order.getId())).isInstanceOf(TtnNotGeneratedException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(outbox);
    }

    @Test
    void ship_withoutAnyShipment_is409() {
        OrderEntity order = stored(order(OrderStatus.PAID));
        delivery(order, null, null);

        assertThatThrownBy(() -> service.ship(order.getId())).isInstanceOf(TtnNotGeneratedException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "SHIPPED", "CANCELLATION_REQUESTED",
            "CANCELLED", "EXPIRED"})
    void ship_inAnotherStatus_is409_withoutAskingDelivery(OrderStatus status) {
        OrderEntity order = stored(order(status));

        assertThatThrownBy(() -> service.ship(order.getId()))
                .isInstanceOfSatisfying(InvalidOrderStateException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(status));
        verifyNoInteractions(shipments);
    }

    @Test
    void ship_anUnknownOrder_is404() {
        UUID orderId = UUID.randomUUID();
        when(orders.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ship(orderId)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void ship_withDeliveryDown_is503() {
        OrderEntity order = stored(order(OrderStatus.PAID));
        when(shipments.find(any())).thenThrow(new DependencyUnavailableException("Delivery", "down", null));

        assertThatThrownBy(() -> service.ship(order.getId())).isInstanceOf(DependencyUnavailableException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void ship_racingTtnGenerated_readsAgain_andShips() {
        OrderEntity order = order(OrderStatus.PAID);
        OrderEntity reread = order(order.getId(), OrderStatus.PENDING_SHIPPING);
        when(orders.findById(order.getId())).thenReturn(Optional.of(order), Optional.of(order),
                Optional.of(reread));
        delivery(order, DeliveryStatus.TTN_GENERATED, TTN);
        when(orders.saveAndFlush(order))
                .thenThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class, order.getId()));

        assertThat(service.ship(order.getId()).status()).isEqualTo(OrderStatus.SHIPPED);
        verify(orders).saveAndFlush(reread);
        verify(outbox).append(any());
    }

    @Test
    void ship_twice_isRefusedTheSecondTime() {
        OrderEntity order = stored(order(OrderStatus.PENDING_SHIPPING));
        delivery(order, DeliveryStatus.TTN_GENERATED, TTN);

        service.ship(order.getId());

        assertThatThrownBy(() -> service.ship(order.getId())).isInstanceOf(InvalidOrderStateException.class);
        verify(outbox, times(1)).append(any());
    }

    private OrderEntity stored(OrderEntity order) {
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        return order;
    }

    private void delivery(OrderEntity order, DeliveryStatus status, String ttn) {
        when(shipments.find(List.of(order.getId())))
                .thenReturn(List.of(new ShipmentInfo(order.getId(), status, ttn, null)));
    }

    private static OrderEntity order(OrderStatus status) {
        return order(UUID.randomUUID(), status);
    }

    private static OrderEntity order(UUID id, OrderStatus status) {
        OrderEntity order = OrderEntity.place(id, UUID.randomUUID(), "ivan@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 2)),
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
            case CANCELLATION_REQUESTED -> order.requestCancellation(NOW);
            case CANCELLED -> {
                order.requestCancellation(NOW);
                order.markCancelled();
            }
            case EXPIRED -> order.expire();
            default -> throw new IllegalArgumentException(status.name());
        }
        return order;
    }
}
