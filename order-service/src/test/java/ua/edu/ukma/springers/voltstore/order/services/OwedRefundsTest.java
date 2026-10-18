package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import ua.edu.ukma.springers.voltstore.order.entities.PendingRefund;
import ua.edu.ukma.springers.voltstore.order.ports.Payments;
import ua.edu.ukma.springers.voltstore.order.repositories.PendingRefundRepository;

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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The refund owed after a lost payment race: stored first, deleted only once Payment confirms it. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class OwedRefundsTest {
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Mock
    private PendingRefundRepository pendingRefunds;

    @Mock
    private Payments payments;

    private OwedRefunds owedRefunds;

    private final OrderEntity order = OrderEntity.place(UUID.randomUUID(), UUID.randomUUID(), "ivan@example.com",
            new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
            List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 2)),
            "UAH", NOW.minusSeconds(900), Duration.ofMinutes(15));

    @BeforeEach
    void setUp() {
        owedRefunds = new OwedRefunds(pendingRefunds, payments, TransactionOperations.withoutTransaction(),
                new CancellationProperties(Duration.ofMinutes(1), 100, List.of(Duration.ofMinutes(1)), 5),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theRefundIsStoredBeforeTheAttempt_andDeletedWhenPaymentConfirms() {
        PendingRefund[] stored = new PendingRefund[1];
        when(pendingRefunds.save(any())).thenAnswer(invocation -> stored[0] = invocation.getArgument(0));
        when(pendingRefunds.findById(order.getId())).thenAnswer(invocation -> Optional.ofNullable(stored[0]));
        when(payments.refund(order.getId(), new BigDecimal("200.00"), "UAH")).thenReturn(new Payments.Refunded());

        owedRefunds.owe(order);

        InOrder steps = inOrder(pendingRefunds, payments);
        steps.verify(pendingRefunds).save(any());
        steps.verify(payments).refund(order.getId(), new BigDecimal("200.00"), "UAH");
        steps.verify(pendingRefunds).deleteById(order.getId());
        assertThat(stored[0].getAmount()).isEqualByComparingTo("200.00");
        assertThat(stored[0].getCurrency()).isEqualTo("UAH");
    }

    @Test
    void alreadyRefunded_countsAsDone() {
        when(pendingRefunds.findById(order.getId()))
                .thenReturn(Optional.of(new PendingRefund(order.getId(), new BigDecimal("200.00"), "UAH", NOW)));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.AlreadyRefunded());

        owedRefunds.attempt(order.getId());

        verify(pendingRefunds).deleteById(order.getId());
    }

    @Test
    void aFailedRefund_staysOwed_withTheFailureCounted() {
        PendingRefund owed = new PendingRefund(order.getId(), new BigDecimal("200.00"), "UAH", NOW.minusSeconds(60));
        when(pendingRefunds.findById(order.getId())).thenReturn(Optional.of(owed));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.RefundFailed("refund_rejected"));

        owedRefunds.attempt(order.getId());

        verify(pendingRefunds, never()).deleteById(any());
        assertThat(owed.getAttempts()).isEqualTo(1);
        assertThat(owed.getLastError()).isEqualTo("refund: refund_rejected");
        assertThat(owed.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(owed.isNeedsManualAttention()).isFalse();
    }

    @Test
    void theFifthFailure_flagsTheRefund_andAlertsOnce(CapturedOutput output) {
        PendingRefund owed = new PendingRefund(order.getId(), new BigDecimal("200.00"), "UAH", NOW);
        when(pendingRefunds.findById(order.getId())).thenReturn(Optional.of(owed));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.RefundFailed("timeout"));

        for (int attempt = 1; attempt <= 6; attempt++) {
            owedRefunds.attempt(order.getId());
        }

        assertThat(owed.isNeedsManualAttention()).isTrue();
        assertThat(owed.getAttempts()).isEqualTo(6);
        assertThat(output.getAll().split("needs manual attention", -1)).hasSize(2);
    }

    @Test
    void aRefundOwedTwice_isStoredOnce() {
        when(pendingRefunds.existsById(order.getId())).thenReturn(true);
        when(pendingRefunds.findById(order.getId()))
                .thenReturn(Optional.of(new PendingRefund(order.getId(), new BigDecimal("200.00"), "UAH", NOW)));
        when(payments.refund(any(), any(), any())).thenReturn(new Payments.Refunded());

        owedRefunds.owe(order);

        verify(pendingRefunds, never()).save(any());
        ArgumentCaptor<UUID> deleted = ArgumentCaptor.forClass(UUID.class);
        verify(pendingRefunds).deleteById(deleted.capture());
        assertThat(deleted.getValue()).isEqualTo(order.getId());
    }
}
