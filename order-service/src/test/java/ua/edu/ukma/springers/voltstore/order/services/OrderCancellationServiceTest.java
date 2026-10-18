package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.dto.CancellationResponse;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.exceptions.CancellationInProgressException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCancellationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final AuthenticatedUser CUSTOMER =
            new AuthenticatedUser(UUID.randomUUID(), UserRole.CUSTOMER, "ivan@example.com");

    @Mock
    private OrderAccess orderAccess;

    @Mock
    private OrderRepository orders;

    @Mock
    private CancellationSaga saga;

    private OrderCancellationService service;

    @BeforeEach
    void setUp() {
        service = new OrderCancellationService(orderAccess, orders, saga, TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theRequestIsStored_beforeTheSagaRuns_andTheSagasOutcomeIsAnswered() {
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(order(OrderStatus.PAID));
        OrderEntity reread = order(OrderStatus.PAID);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(reread));
        when(saga.run(ORDER_ID)).thenReturn(OrderStatus.CANCELLED);

        CancellationResponse response = service.cancel(ORDER_ID, CUSTOMER);

        assertThat(response).isEqualTo(new CancellationResponse(ORDER_ID, OrderStatus.CANCELLED));
        assertThat(reread.getStatus()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
        assertThat(reread.getStatusBeforeCancellation()).isEqualTo(OrderStatus.PAID);
        assertThat(reread.getCancellationLastAttemptAt()).isEqualTo(NOW);
        InOrder steps = inOrder(orders, saga);
        steps.verify(orders).saveAndFlush(reread);
        steps.verify(saga).run(ORDER_ID);
    }

    @Test
    void aFailedStep_isStillAccepted_asCancellationRequested() {
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(order(OrderStatus.PENDING_PAYMENT));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.PENDING_PAYMENT)));
        when(saga.run(ORDER_ID)).thenReturn(OrderStatus.CANCELLATION_REQUESTED);

        assertThat(service.cancel(ORDER_ID, CUSTOMER).status()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
    }

    @Test
    void aCancellationInProgress_is409_withoutAnotherSaga() {
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(order(OrderStatus.CANCELLATION_REQUESTED));

        assertThatThrownBy(() -> service.cancel(ORDER_ID, CUSTOMER))
                .isInstanceOf(CancellationInProgressException.class)
                .hasMessageContaining("already in progress");
        verifyNoInteractions(saga, orders);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "DELIVERED", "CANCELLED", "EXPIRED"})
    void anOrderPastTheCancellableStatuses_is409(OrderStatus status) {
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(order(status));

        assertThatThrownBy(() -> service.cancel(ORDER_ID, CUSTOMER))
                .isInstanceOfSatisfying(InvalidOrderStateException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(status));
        verifyNoInteractions(saga);
    }

    @Test
    void aSecondRequestThatLostTheRace_is409_inProgress() {
        // Both requests saw PAID; the other one committed the cancellation first
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(order(OrderStatus.PAID));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CANCELLATION_REQUESTED)));

        assertThatThrownBy(() -> service.cancel(ORDER_ID, CUSTOMER))
                .isInstanceOf(CancellationInProgressException.class);
        verifyNoInteractions(saga);
    }

    @Test
    void aPaymentCommittedMeanwhile_isReadAgain_soTheRefundIsOwed() {
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(order(OrderStatus.PENDING_PAYMENT));
        OrderEntity stale = order(OrderStatus.PENDING_PAYMENT);
        OrderEntity paid = order(OrderStatus.PAID);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(stale), Optional.of(paid));
        when(orders.saveAndFlush(stale))
                .thenThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class, ORDER_ID));
        when(saga.run(ORDER_ID)).thenReturn(OrderStatus.CANCELLED);

        service.cancel(ORDER_ID, CUSTOMER);

        verify(orders).saveAndFlush(paid);
        assertThat(paid.getStatusBeforeCancellation()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.refundOwed()).isTrue();
    }

    @Test
    void endlessConflicts_giveUpAfterThreeAttempts() {
        when(orderAccess.loadOwned(ORDER_ID, CUSTOMER)).thenReturn(order(OrderStatus.PAID));
        when(orders.findById(ORDER_ID)).thenAnswer(invocation -> Optional.of(order(OrderStatus.PAID)));
        when(orders.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class, ORDER_ID));

        assertThatThrownBy(() -> service.cancel(ORDER_ID, CUSTOMER))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verifyNoInteractions(saga);
    }

    private static OrderEntity order(OrderStatus status) {
        OrderEntity order = OrderEntity.place(ORDER_ID, CUSTOMER.id(), CUSTOMER.email(),
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 2)),
                "UAH", NOW.minusSeconds(600), Duration.ofMinutes(15));
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
            case CANCELLATION_REQUESTED -> order.requestCancellation(NOW);
            case CANCELLED -> {
                order.requestCancellation(NOW);
                order.markCancelled();
            }
            case EXPIRED -> order.expire();
        }
        return order;
    }
}
