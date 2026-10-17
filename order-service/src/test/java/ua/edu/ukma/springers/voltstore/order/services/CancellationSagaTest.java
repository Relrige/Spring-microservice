package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.CancellationProperties;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderCancelledEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.Payments;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The saga's steps per start status (the table of the state machine's Cancellation Scope), failures, and repeats.
 * The repository returns one stored instance for every read, so each run sees what the previous one saved.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class CancellationSagaTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final BigDecimal TOTAL = new BigDecimal("200.00");

    @Mock
    private OrderRepository orders;

    @Mock
    private StockReservations stock;

    @Mock
    private Shipments shipments;

    @Mock
    private Payments payments;

    @Mock
    private Outbox outbox;

    private CancellationSaga saga;

    @BeforeEach
    void setUp() {
        saga = new CancellationSaga(orders, stock, shipments, payments, outbox,
                TransactionOperations.withoutTransaction(),
                new CancellationProperties(Duration.ofMinutes(1), 100,
                        List.of(Duration.ofMinutes(1), Duration.ofMinutes(5)), 5),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.Refunded());
    }

    @Test
    void cancelledBeforePayment_onlyReleasesTheStock() {
        OrderEntity order = stored(cancelling(OrderStatus.PENDING_PAYMENT));

        assertThat(saga.run(order.getId())).isEqualTo(OrderStatus.CANCELLED);

        verify(stock).release(order.getId());
        verifyNoInteractions(shipments, payments);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PENDING_SHIPPING"})
    void cancelledAfterPayment_releasesCancelsTheShipmentAndRefunds_inThisOrder(OrderStatus start) {
        OrderEntity order = stored(cancelling(start));

        assertThat(saga.run(order.getId())).isEqualTo(OrderStatus.CANCELLED);

        InOrder steps = inOrder(stock, shipments, payments, outbox);
        steps.verify(stock).release(order.getId());
        steps.verify(shipments).cancel(order.getId());
        steps.verify(payments).refund(order.getId(), TOTAL, "UAH");
        steps.verify(outbox).append(any(OrderCancelledEvent.class));
    }

    @Test
    void success_writesOrderCancelled_withTheCustomersReason() {
        OrderEntity order = stored(cancelling(OrderStatus.PAID));

        saga.run(order.getId());

        ArgumentCaptor<OrderCancelledEvent> event = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(outbox).append(event.capture());
        assertThat(event.getValue().orderId()).isEqualTo(order.getId());
        assertThat(event.getValue().reason()).isEqualTo(OrderCancelledEvent.Reason.CUSTOMER_CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void anAlreadyRefundedOrder_countsAsRefunded() {
        OrderEntity order = stored(cancelling(OrderStatus.PAID));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.AlreadyRefunded());

        assertThat(saga.run(order.getId())).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void aFailedRelease_leavesTheOrderCancelling_withTheFailureStored_andStopsThere() {
        OrderEntity order = stored(cancelling(OrderStatus.PAID));
        doThrow(new DependencyUnavailableException("Inventory", "timeout", null)).when(stock).release(any());

        assertThat(saga.run(order.getId())).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
        assertThat(order.getCancellationAttempts()).isEqualTo(1);
        assertThat(order.getCancellationFailure()).startsWith("release: ").contains("Inventory");
        assertThat(order.getCancellationLastAttemptAt()).isEqualTo(NOW);
        verifyNoInteractions(shipments, payments, outbox);
    }

    @Test
    void aFailedShipmentCancel_isStored_andNoRefundIsTried() {
        OrderEntity order = stored(cancelling(OrderStatus.PAID));
        doThrow(new DependencyUnavailableException("Delivery", "down", null)).when(shipments).cancel(any());

        saga.run(order.getId());

        assertThat(order.getCancellationFailure()).startsWith("delivery cancel: ");
        verify(payments, never()).refund(any(), any(), any());
    }

    @Test
    void aRejectedRefund_isAFailedStepLikeAnyOther() {
        OrderEntity order = stored(cancelling(OrderStatus.PENDING_SHIPPING));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.RefundFailed("refund_rejected"));

        assertThat(saga.run(order.getId())).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
        assertThat(order.getCancellationFailure()).isEqualTo("refund: refund_rejected");
        verifyNoInteractions(outbox);
    }

    @Test
    void aRepeatAfterAPartialSuccess_runsEveryStepAgain_andFinishes() {
        // Release and shipment cancel succeeded the first time; the refund failed. The repeat calls all three
        // again: each is idempotent in its service, so the repeated ones have no second effect there
        OrderEntity order = stored(cancelling(OrderStatus.PAID));
        when(payments.refund(any(), any(), any()))
                .thenReturn(new Payments.RefundFailed("timeout"), new Payments.AlreadyRefunded());

        assertThat(saga.run(order.getId())).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
        assertThat(saga.run(order.getId())).isEqualTo(OrderStatus.CANCELLED);

        verify(stock, times(2)).release(order.getId());
        verify(shipments, times(2)).cancel(order.getId());
        verify(payments, times(2)).refund(order.getId(), TOTAL, "UAH");
        verify(outbox, times(1)).append(any());
        assertThat(order.getCancellationAttempts()).isEqualTo(1);
    }

    @Test
    void theFifthFailure_flagsTheOrder_andAlertsOnce(CapturedOutput output) {
        OrderEntity order = stored(cancelling(OrderStatus.PAID));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.RefundFailed("refund_rejected"));

        for (int attempt = 1; attempt <= 4; attempt++) {
            saga.run(order.getId());
        }
        assertThat(order.isNeedsManualAttention()).isFalse();
        saga.run(order.getId());
        saga.run(order.getId());

        assertThat(order.getCancellationAttempts()).isEqualTo(6);
        assertThat(order.isNeedsManualAttention()).isTrue();
        assertThat(output.getAll().split("needs manual attention", -1)).hasSize(2);
    }

    @Test
    void anOrderNoLongerCancelling_isLeftAlone() {
        OrderEntity order = stored(cancelling(OrderStatus.PAID));
        order.markCancelled();

        assertThat(saga.run(order.getId())).isEqualTo(OrderStatus.CANCELLED);
        verifyNoInteractions(stock, shipments, payments, outbox);
    }

    @Test
    void aLateTtnGenerated_cannotMoveACancellingOrderBackToPendingShipping() {
        OrderEntity order = cancelling(OrderStatus.PAID);

        org.assertj.core.api.Assertions.assertThatThrownBy(order::markPendingShipping)
                .isInstanceOf(ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
    }

    private OrderEntity stored(OrderEntity order) {
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        return order;
    }

    private static OrderEntity cancelling(OrderStatus from) {
        OrderEntity order = OrderEntity.place(UUID.randomUUID(), UUID.randomUUID(), "ivan@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 2)),
                "UAH", NOW.minusSeconds(600), Duration.ofMinutes(15));
        if (from != OrderStatus.PENDING_PAYMENT) {
            order.markPaid(NOW.minusSeconds(500));
        }
        if (from == OrderStatus.PENDING_SHIPPING) {
            order.markPendingShipping();
        }
        order.requestCancellation(NOW.minusSeconds(60));
        return order;
    }
}
