package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.dto.CardDetailsRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PaidOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderPaidEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentAttemptInProgressException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentWindowExpiredException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.Payments;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.order.security.UserRole;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every Payment answer of EP-ORD-03, and the races with cancellation and expiry. The repository returns a separate
 * instance for the re-read, as JPA does in the second transaction, so a status change "during the charge" is a
 * different object in a different state.
 */
@ExtendWith(MockitoExtension.class)
class OrderPaymentServiceTest {
    private static final Instant PLACED = Instant.parse("2026-10-17T10:00:00Z");
    private static final Instant NOW = PLACED.plus(Duration.ofMinutes(5));
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final AuthenticatedUser CUSTOMER =
            new AuthenticatedUser(UUID.randomUUID(), UserRole.CUSTOMER, "ivan@example.com");
    private static final CardDetailsRequest CARD = new CardDetailsRequest("4242424242424242", 12, 2099, "123");

    @Mock
    private OrderAccess orderAccess;

    @Mock
    private OrderRepository orders;

    @Mock
    private Payments payments;

    @Mock
    private Outbox outbox;

    private OrderPaymentService service;

    @BeforeEach
    void setUp() {
        service = new OrderPaymentService(orderAccess, orders, payments, outbox,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void paid_marksTheOrderPaid_andWritesTheFatOrderPaid() {
        OrderEntity reread = givenPendingOrder();
        charging(new Payments.Paid(UUID.randomUUID()));

        PaidOrderResponse response = service.pay(ORDER_ID, CUSTOMER, CARD);

        assertThat(response).isEqualTo(new PaidOrderResponse(ORDER_ID, OrderStatus.PAID));
        assertThat(reread.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(reread.getPaidAt()).isEqualTo(NOW);
        verify(orders).saveAndFlush(reread);
        verify(outbox).append(any(OrderPaidEvent.class));
        // The amount is the order's total, never the client's
        verify(payments).charge(eq(ORDER_ID), eq(new BigDecimal("200.00")), eq("UAH"),
                eq(new Payments.PaymentCard("4242424242424242", 12, 2099, "123")));
    }

    @Test
    void alreadyCharged_countsAsPaid() {
        OrderEntity reread = givenPendingOrder();
        charging(new Payments.AlreadyCharged());

        assertThat(service.pay(ORDER_ID, CUSTOMER, CARD).status()).isEqualTo(OrderStatus.PAID);
        assertThat(reread.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void declined_is402_andLeavesTheOrderAndItsWindowUnchanged() {
        OrderEntity loaded = givenLoadedOrder();
        charging(new Payments.Declined("insufficient_funds"));

        assertThatThrownBy(() -> service.pay(ORDER_ID, CUSTOMER, CARD))
                .isInstanceOfSatisfying(PaymentDeclinedException.class,
                        e -> assertThat(e.getReason()).isEqualTo("insufficient_funds"));
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(loaded.getReservationExpiresAt()).isEqualTo(PLACED.plus(Duration.ofMinutes(15)));
        verifyNoInteractions(orders, outbox);
    }

    @Test
    void attemptInProgress_asksToRetryWithTheSameCard() {
        givenLoadedOrder();
        charging(new Payments.AttemptInProgress());

        assertThatThrownBy(() -> service.pay(ORDER_ID, CUSTOMER, CARD))
                .isInstanceOf(PaymentAttemptInProgressException.class)
                .hasMessageContaining("same card");
        verifyNoInteractions(orders, outbox);
    }

    @Test
    void paymentUnavailable_is503_andLeavesTheOrderUnchanged() {
        givenLoadedOrder();
        charging(new Payments.ChargeUnavailable("timeout"));

        assertThatThrownBy(() -> service.pay(ORDER_ID, CUSTOMER, CARD))
                .isInstanceOf(DependencyUnavailableException.class);
        verifyNoInteractions(orders, outbox);
    }

    @Test
    void anOrderNotAwaitingPayment_is409_withoutACharge() {
        OrderEntity order = order();
        order.markPaid(PLACED.plusSeconds(60));
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(order);

        assertThatThrownBy(() -> service.pay(ORDER_ID, CUSTOMER, CARD))
                .isInstanceOfSatisfying(InvalidOrderStateException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(OrderStatus.PAID));
        verifyNoInteractions(payments);
    }

    @Test
    void anElapsedWindow_isRefused_withoutACharge() {
        // The window ends exactly now: no longer payable
        OrderEntity order = OrderEntity.place(ORDER_ID, CUSTOMER.id(), CUSTOMER.email(), delivery(), items(), "UAH",
                NOW.minus(Duration.ofMinutes(15)), Duration.ofMinutes(15));
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(order);

        assertThatThrownBy(() -> service.pay(ORDER_ID, CUSTOMER, CARD))
                .isInstanceOf(PaymentWindowExpiredException.class);
        verifyNoInteractions(payments);
    }

    @Test
    void cancelledWhileTheCardWasCharged_refundsAtOnce_andIs409() {
        givenLoadedOrder();
        charging(new Payments.Paid(UUID.randomUUID()));
        // The customer cancelled while the charge ran: the re-read finds the cancellation
        OrderEntity reread = order();
        reread.requestCancellation(NOW);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(reread));
        when(payments.refund(ORDER_ID, new BigDecimal("200.00"), "UAH")).thenReturn(new Payments.Refunded());

        assertThatThrownBy(() -> service.pay(ORDER_ID, CUSTOMER, CARD))
                .isInstanceOfSatisfying(InvalidOrderStateException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED));
        verify(payments).refund(ORDER_ID, new BigDecimal("200.00"), "UAH");
        verify(outbox, never()).append(any());
    }

    @Test
    void expiredWhileTheCardWasCharged_refundsAtOnce() {
        givenLoadedOrder();
        charging(new Payments.AlreadyCharged());
        OrderEntity reread = order();
        reread.expire();
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(reread));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.AlreadyRefunded());

        assertThatThrownBy(() -> service.pay(ORDER_ID, CUSTOMER, CARD))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(payments).refund(ORDER_ID, new BigDecimal("200.00"), "UAH");
    }

    @Test
    void aFailedRefund_isStill409() {
        givenLoadedOrder();
        charging(new Payments.Paid(UUID.randomUUID()));
        OrderEntity reread = order();
        reread.expire();
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(reread));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.RefundFailed("refund_rejected"));

        assertThatThrownBy(() -> service.pay(ORDER_ID, CUSTOMER, CARD))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void paidMeanwhileByAParallelRequest_is200_withoutRefundOrSecondEvent() {
        givenLoadedOrder();
        charging(new Payments.AlreadyCharged());
        OrderEntity reread = order();
        reread.markPaid(NOW.minusSeconds(1));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(reread));

        assertThat(service.pay(ORDER_ID, CUSTOMER, CARD).status()).isEqualTo(OrderStatus.PAID);
        verify(payments, never()).refund(any(), any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void aConcurrentChangeAtCommit_isReadAgain_andDecidedAgain() {
        givenLoadedOrder();
        charging(new Payments.Paid(UUID.randomUUID()));
        OrderEntity first = order();
        OrderEntity second = order();
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(first), Optional.of(second));
        when(orders.saveAndFlush(first)).thenThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class,
                ORDER_ID));

        assertThat(service.pay(ORDER_ID, CUSTOMER, CARD).status()).isEqualTo(OrderStatus.PAID);
        verify(orders, times(2)).findById(ORDER_ID);
        verify(orders).saveAndFlush(second);
    }

    @Test
    void endlessConcurrentChanges_giveUpAfterThreeAttempts() {
        givenLoadedOrder();
        charging(new Payments.Paid(UUID.randomUUID()));
        when(orders.findById(ORDER_ID)).thenAnswer(invocation -> Optional.of(order()));
        when(orders.saveAndFlush(any())).thenThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class,
                ORDER_ID));

        assertThatThrownBy(() -> service.pay(ORDER_ID, CUSTOMER, CARD))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(orders, times(3)).findById(ORDER_ID);
    }

    @Test
    void theCardNeverAppearsInToString() {
        assertThat(CARD.toString()).doesNotContain("4242424242424242").doesNotContain("123]").contains("4242");
    }

    /** The order as loaded before the charge, and a separate instance for the re-read after it. */
    private OrderEntity givenPendingOrder() {
        givenLoadedOrder();
        OrderEntity reread = order();
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(reread));
        return reread;
    }

    private OrderEntity givenLoadedOrder() {
        OrderEntity loaded = order();
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(loaded);
        return loaded;
    }

    private void charging(Payments.ChargeResult result) {
        when(payments.charge(any(), any(), any(), any())).thenReturn(result);
    }

    private static OrderEntity order() {
        return OrderEntity.place(ORDER_ID, CUSTOMER.id(), CUSTOMER.email(), delivery(), items(), "UAH", PLACED,
                Duration.ofMinutes(15));
    }

    private static DeliveryDetails delivery() {
        return new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA");
    }

    private static List<OrderItemEntity> items() {
        return List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 2));
    }
}
