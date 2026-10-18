package ua.edu.ukma.springers.voltstore.order.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.clients.ClientsProperties;
import ua.edu.ukma.springers.voltstore.order.config.ExpiryProperties;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderCancelledEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentWindowExpiryJobTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(30);
    private static final ExpiryProperties PROPERTIES = new ExpiryProperties(Duration.ofMinutes(1), GRACE, 100);

    @Mock
    private OrderRepository orders;

    @Mock
    private StockReservations stock;

    @Mock
    private Outbox outbox;

    @Test
    void aGraceNotLongerThanThePaymentReadTimeout_isRefusedAtStartup() {
        assertThatThrownBy(() -> job(clients(Duration.ofSeconds(30))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order.expiry.grace");
    }

    @Test
    void asksOnlyForOrdersPastTheWindowPlusGrace() {
        job(clients(Duration.ofSeconds(8))).expireDueOrders();

        verify(orders).findExpired(NOW.minus(GRACE), Limit.of(100));
    }

    @Test
    void anExpiredOrder_releasesItsStock_thenExpires_withOrderCancelled() {
        OrderEntity order = stored(expiredOrder());

        job(clients(Duration.ofSeconds(8))).expireDueOrders();

        verify(stock).release(order.getId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(orders).saveAndFlush(order);
        ArgumentCaptor<OrderCancelledEvent> event = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(outbox).append(event.capture());
        assertThat(event.getValue().reason()).isEqualTo(OrderCancelledEvent.Reason.EXPIRED);
    }

    @Test
    void aFailedRelease_keepsTheOrderPendingForTheNextRun() {
        OrderEntity order = stored(expiredOrder());
        doThrow(new DependencyUnavailableException("Inventory", "down", null)).when(stock).release(any());

        job(clients(Duration.ofSeconds(8))).expireDueOrders();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        verify(orders, never()).saveAndFlush(any());
        verify(outbox, never()).append(any());
    }

    @Test
    void anOrderPaidBetweenSelectionAndCheckAndSet_isNotExpired() {
        OrderEntity selected = expiredOrder();
        when(orders.findExpired(any(), any())).thenReturn(List.of(selected));
        OrderEntity paid = expiredOrder(selected.getId());
        paid.markPaid(NOW.minusSeconds(5));
        when(orders.findById(selected.getId())).thenReturn(Optional.of(paid));

        job(clients(Duration.ofSeconds(8))).expireDueOrders();

        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(outbox, never()).append(any());
    }

    @Test
    void oneFailingOrder_doesNotStopTheRun() {
        OrderEntity broken = expiredOrder();
        OrderEntity next = expiredOrder();
        when(orders.findExpired(any(), any())).thenReturn(List.of(broken, next));
        when(orders.findById(broken.getId())).thenThrow(new IllegalStateException("bug"));
        when(orders.findById(next.getId())).thenReturn(Optional.of(next));

        job(clients(Duration.ofSeconds(8))).expireDueOrders();

        assertThat(next.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(stock).release(eq(next.getId()));
    }

    private OrderEntity stored(OrderEntity order) {
        when(orders.findExpired(any(), any())).thenReturn(List.of(order));
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        return order;
    }

    private PaymentWindowExpiryJob job(ClientsProperties clients) {
        return new PaymentWindowExpiryJob(orders, stock, outbox, TransactionOperations.withoutTransaction(),
                PROPERTIES, clients, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ClientsProperties clients(Duration paymentReadTimeout) {
        ClientsProperties.Endpoint any = new ClientsProperties.Endpoint("http://localhost", Duration.ofSeconds(2),
                Duration.ofSeconds(3));
        return new ClientsProperties(any, any, new ClientsProperties.Endpoint("http://localhost",
                Duration.ofSeconds(2), paymentReadTimeout), any);
    }

    private static OrderEntity expiredOrder() {
        return expiredOrder(UUID.randomUUID());
    }

    // Placed 16 minutes ago with a 15-minute window: expired a minute ago, past the 30 s grace
    private static OrderEntity expiredOrder(UUID id) {
        return OrderEntity.place(id, UUID.randomUUID(), "ivan@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 1)),
                "UAH", NOW.minus(Duration.ofMinutes(16)), Duration.ofMinutes(15));
    }
}
